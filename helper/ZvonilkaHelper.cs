// Помощник Звонилки — удалённая помощь на компьютере с Windows.
//
// Пока окно открыто, звонилка в браузере этого компьютера может передавать сюда движения мыши и нажатия
// клавиш собеседника — и только после того, как в звонке нажали «Разрешить». Сам помощник в интернет
// не ходит: он слушает только этот компьютер (127.0.0.1) и принимает только страницу звонилки с сайта.
//
// Сборка компилятором, который есть в Windows (.NET Framework 4):
//   csc /target:winexe /r:System.Web.Extensions.dll /win32icon:icon.ico ZvonilkaHelper.cs
// Для проверки без настоящих нажатий: --dry (только записывать), --dev (пускать localhost), --headless, --log=путь.

using System;
using System.Collections.Generic;
using System.Drawing;
using System.Globalization;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;
using System.Windows.Forms;

static class ZvonilkaHelper
{
    const int Port = 47231;   // тот же номер — в index.html (HELPER_URL)
    static readonly string[] Origins = { "https://valiant22assault-hue.github.io" };
    static readonly string[] DevOrigins = { "http://localhost:8765", "http://127.0.0.1:8765" };

    // Помощник подключается не сразу, а когда в звонке нажмут «Разрешить», — до этого он просто ждёт.
    const string Waiting = "Готов. Подключится, когда в звонке нажмут «Управлять» и вы ответите «Разрешить».";
    static bool dev, dry, headless;
    static string logPath;
    static Form form;
    static Label status;
    static volatile bool closing, connected;
    static DateTime lastInput = DateTime.MinValue;
    static TcpClient current;
    static readonly object clientLock = new object(), inputLock = new object();

    [STAThread]
    static void Main(string[] args)
    {
        foreach (string a in args)
        {
            if (a == "--dev") dev = true;
            else if (a == "--dry") dry = true;
            else if (a == "--headless") headless = true;
            else if (a.StartsWith("--log=")) logPath = a.Substring(6);
        }
        TcpListener listener;
        try
        {
            listener = new TcpListener(IPAddress.Loopback, Port);
            listener.Start();
        }
        catch (SocketException)
        {
            if (!headless) MessageBox.Show("Помощник Звонилки уже запущен.", "Помощник Звонилки");
            return;
        }
        Log("запущен" + (dry ? " (проверка: без настоящих нажатий)" : ""));
        Thread server = new Thread(() => Serve(listener));
        server.IsBackground = true;
        server.Start();
        if (headless) { server.Join(); return; }

        Application.EnableVisualStyles();
        form = BuildForm();
        Application.Run(form);
        closing = true;
        ReleaseAll();
        try { listener.Stop(); } catch { }
    }

    // ---------- окно ----------

    static Form BuildForm()
    {
        Form f = new Form();
        f.Text = "Помощник Звонилки";
        f.ClientSize = new Size(440, 200);
        f.FormBorderStyle = FormBorderStyle.FixedDialog;
        f.MaximizeBox = false;
        f.TopMost = true;
        f.StartPosition = FormStartPosition.CenterScreen;
        f.BackColor = Color.FromArgb(36, 39, 45);
        f.ForeColor = Color.FromArgb(238, 240, 243);
        f.Font = new Font("Segoe UI", 10.5f);
        try { f.Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }

        Label text = new Label();
        text.Text = "Удалённая помощь включена. Пока это окно открыто, собеседник в Звонилке " +
                    "может управлять мышью и клавиатурой — только если вы разрешите это в звонке.";
        text.SetBounds(18, 14, 404, 64);

        status = new Label();
        status.Text = Waiting;
        status.ForeColor = Color.FromArgb(169, 175, 185);
        status.SetBounds(18, 84, 404, 44);

        Button stop = new Button();
        stop.Text = "Остановить и закрыть";
        stop.SetBounds(18, 138, 404, 44);
        stop.FlatStyle = FlatStyle.Flat;
        stop.FlatAppearance.BorderSize = 0;
        stop.BackColor = Color.FromArgb(229, 72, 77);
        stop.ForeColor = Color.White;
        stop.Click += (s, e) => f.Close();

        f.Controls.AddRange(new Control[] { text, status, stop });

        // Строка состояния: ждём, подключено, прямо сейчас управляют.
        System.Windows.Forms.Timer timer = new System.Windows.Forms.Timer();
        timer.Interval = 500;
        timer.Tick += (s, e) =>
        {
            bool busy = (DateTime.Now - lastInput).TotalSeconds < 2;
            status.Text = !connected ? Waiting
                : busy ? "Сейчас вашим компьютером управляет собеседник."
                : "Подключено: собеседник может управлять, пока вы не нажмёте «Остановить».";
            status.ForeColor = busy ? Color.FromArgb(255, 143, 146) : Color.FromArgb(169, 175, 185);
        };
        timer.Start();
        return f;
    }

    // ---------- WebSocket на 127.0.0.1 ----------

    static void Serve(TcpListener listener)
    {
        while (!closing)
        {
            TcpClient client;
            try { client = listener.AcceptTcpClient(); }
            catch { return; }
            Thread t = new Thread(() => Handle(client));
            t.IsBackground = true;
            t.Start();
        }
    }

    static void Handle(TcpClient client)
    {
        using (client)
        {
            NetworkStream stream = client.GetStream();
            string head = ReadHead(stream);
            if (head == null) return;
            string key = Header(head, "Sec-WebSocket-Key"), origin = Header(head, "Origin");
            // Только страница звонилки: чужой сайт, открытый в браузере, сюда не подключится.
            if (key == null || !Allowed(origin))
            {
                Write(stream, "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                Log("отказ: " + (origin ?? "без Origin"));
                return;
            }
            string accept = Convert.ToBase64String(SHA1.Create().ComputeHash(
                Encoding.ASCII.GetBytes(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")));
            Write(stream, "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                          "Sec-WebSocket-Accept: " + accept + "\r\n\r\n");
            lock (clientLock)
            {
                if (current != null) try { current.Close(); } catch { }
                current = client;
            }
            connected = true;
            Log("звонилка подключена: " + origin);
            SendText(stream, "{\"type\":\"hello\",\"version\":1}");
            try { Frames(stream); }
            catch (Exception e) { Log("соединение закрыто: " + e.Message); }
            ReleaseAll();
            lock (clientLock)
            {
                if (current == client) { current = null; connected = false; }
            }
        }
    }

    static string ReadHead(NetworkStream s)
    {
        List<byte> buf = new List<byte>();
        while (buf.Count < 8192)
        {
            int b = s.ReadByte();
            if (b < 0) return null;
            buf.Add((byte)b);
            int n = buf.Count;
            if (n >= 4 && buf[n - 4] == 13 && buf[n - 3] == 10 && buf[n - 2] == 13 && buf[n - 1] == 10)
                return Encoding.ASCII.GetString(buf.ToArray());
        }
        return null;
    }

    static string Header(string head, string name)
    {
        foreach (string line in head.Split(new[] { "\r\n" }, StringSplitOptions.None))
        {
            int i = line.IndexOf(':');
            if (i > 0 && string.Equals(line.Substring(0, i).Trim(), name, StringComparison.OrdinalIgnoreCase))
                return line.Substring(i + 1).Trim();
        }
        return null;
    }

    static bool Allowed(string origin)
    {
        if (origin == null) return false;
        origin = origin.TrimEnd('/');
        foreach (string o in Origins) if (origin == o) return true;
        if (dev) foreach (string o in DevOrigins) if (origin == o) return true;
        return false;
    }

    static void Frames(NetworkStream s)
    {
        while (!closing)
        {
            byte[] h = ReadN(s, 2);
            int op = h[0] & 15;
            bool masked = (h[1] & 128) != 0;
            long len = h[1] & 127;
            if (len == 126) { byte[] b = ReadN(s, 2); len = (b[0] << 8) | b[1]; }
            else if (len == 127) { byte[] b = ReadN(s, 8); len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | b[i]; }
            if (len > 65536) throw new IOException("слишком большое сообщение");
            byte[] mask = masked ? ReadN(s, 4) : null;
            byte[] data = ReadN(s, (int)len);
            if (masked) for (int i = 0; i < data.Length; i++) data[i] ^= mask[i & 3];
            if (op == 8) { try { s.Write(new byte[] { 0x88, 0 }, 0, 2); } catch { } return; }
            if (op == 9) { SendFrame(s, 0x8A, data); continue; }
            if (op == 1) OnMessage(Encoding.UTF8.GetString(data));
        }
    }

    static byte[] ReadN(Stream s, int n)
    {
        byte[] b = new byte[n];
        int o = 0;
        while (o < n)
        {
            int r = s.Read(b, o, n - o);
            if (r <= 0) throw new IOException("соединение закрыто");
            o += r;
        }
        return b;
    }

    static void Write(Stream s, string text)
    {
        byte[] b = Encoding.ASCII.GetBytes(text);
        s.Write(b, 0, b.Length);
    }

    static void SendText(Stream s, string text)
    {
        SendFrame(s, 0x81, Encoding.UTF8.GetBytes(text));
    }

    static void SendFrame(Stream s, byte first, byte[] data)
    {
        byte[] head = data.Length < 126 ? new byte[] { first, (byte)data.Length }
                                        : new byte[] { first, 126, (byte)(data.Length >> 8), (byte)data.Length };
        lock (s)
        {
            s.Write(head, 0, head.Length);
            s.Write(data, 0, data.Length);
        }
    }

    // ---------- команды от звонилки ----------
    // ["m", x, y] — мышь в долях экрана (0…1); ["d"/"u", кнопка, x, y]; ["w", ±1] — колесо;
    // ["k", 1|0, code, key, модификаторы: 1 Ctrl, 2 Alt, 4 Win].

    static readonly JavaScriptSerializer json = new JavaScriptSerializer();

    static void OnMessage(string text)
    {
        object[] e;
        try { e = json.DeserializeObject(text) as object[]; }
        catch { return; }
        if (e == null || e.Length == 0) return;
        string kind = e[0] as string;
        try
        {
            lock (inputLock)
            {
                if (kind == "m") Mouse(Num(e, 1), Num(e, 2), 0);
                else if (kind == "d" || kind == "u") Button((int)Num(e, 1), kind == "d", Num(e, 2), Num(e, 3));
                else if (kind == "w") Wheel((int)Num(e, 1));
                else if (kind == "k") Key(Num(e, 1) == 1, Str(e, 2), Str(e, 3), (int)Num(e, 4));
            }
        }
        catch (Exception ex) { Log("ошибка: " + ex.Message); }
    }

    static double Num(object[] e, int i)
    {
        return i < e.Length && e[i] != null ? Convert.ToDouble(e[i], CultureInfo.InvariantCulture) : 0;
    }

    static string Str(object[] e, int i)
    {
        return i < e.Length ? e[i] as string : null;
    }

    // ---------- нажатия (SendInput) ----------

    const uint INPUT_MOUSE = 0, INPUT_KEYBOARD = 1;
    const uint MOVE = 0x0001, LEFTDOWN = 0x0002, LEFTUP = 0x0004, RIGHTDOWN = 0x0008, RIGHTUP = 0x0010,
               MIDDLEDOWN = 0x0020, MIDDLEUP = 0x0040, WHEEL = 0x0800, ABSOLUTE = 0x8000;
    const uint EXTENDEDKEY = 0x0001, KEYUP = 0x0002, UNICODE = 0x0004;

    [StructLayout(LayoutKind.Sequential)]
    struct MOUSEINPUT { public int dx, dy; public uint mouseData, dwFlags, time; public IntPtr dwExtraInfo; }

    [StructLayout(LayoutKind.Sequential)]
    struct KEYBDINPUT { public ushort wVk, wScan; public uint dwFlags, time; public IntPtr dwExtraInfo; }

    [StructLayout(LayoutKind.Explicit)]
    struct INPUTUNION { [FieldOffset(0)] public MOUSEINPUT mi; [FieldOffset(0)] public KEYBDINPUT ki; }

    [StructLayout(LayoutKind.Sequential)]
    struct INPUT { public uint type; public INPUTUNION u; }

    [DllImport("user32.dll", SetLastError = true)]
    static extern uint SendInput(uint count, INPUT[] inputs, int size);

    static readonly HashSet<int> buttonsDown = new HashSet<int>();
    static readonly HashSet<ushort> keysDown = new HashSet<ushort>();

    static void Inject(INPUT[] inputs, string what)
    {
        lastInput = DateTime.Now;
        if (dry) { Log(what); return; }
        SendInput((uint)inputs.Length, inputs, Marshal.SizeOf(typeof(INPUT)));
    }

    // Показан весь экран — доли переводятся в координаты основного монитора (0…65535).
    static void Mouse(double x, double y, uint flags)
    {
        x = Math.Max(0, Math.Min(1, x));
        y = Math.Max(0, Math.Min(1, y));
        INPUT i = new INPUT();
        i.type = INPUT_MOUSE;
        i.u.mi.dx = (int)Math.Round(x * 65535);
        i.u.mi.dy = (int)Math.Round(y * 65535);
        i.u.mi.dwFlags = flags | MOVE | ABSOLUTE;
        Inject(new[] { i }, string.Format(CultureInfo.InvariantCulture, "мышь {0:0.000} {1:0.000}{2}", x, y,
            flags == 0 ? "" : " кнопка " + flags.ToString("X")));
    }

    static void Button(int b, bool down, double x, double y)
    {
        uint f = b == 2 ? (down ? RIGHTDOWN : RIGHTUP) : b == 1 ? (down ? MIDDLEDOWN : MIDDLEUP) : (down ? LEFTDOWN : LEFTUP);
        if (down) buttonsDown.Add(b); else buttonsDown.Remove(b);
        Mouse(x, y, f);
    }

    static void Wheel(int dir)
    {
        INPUT i = new INPUT();
        i.type = INPUT_MOUSE;
        i.u.mi.dwFlags = WHEEL;
        i.u.mi.mouseData = unchecked((uint)(-Math.Sign(dir) * 120));   // в браузере вниз — плюс, в Windows — минус
        Inject(new[] { i }, "колесо " + dir);
    }

    static void Key(bool down, string code, string key, int mods)
    {
        bool combo = (mods & 7) != 0;
        bool printable = key != null && (key.Length == 1 || (key.Length == 2 && char.IsSurrogatePair(key, 0)));
        // Буквы и знаки печатаем символом как есть: неважно, какая раскладка включена на этом компьютере.
        // С зажатым Ctrl, Alt или Win нужны коды клавиш — иначе не сработают Ctrl+C и подобные.
        if (printable && !combo)
        {
            if (down) Unicode(key);
            return;
        }
        ushort vk = Vk(code);
        if (vk != 0) KeyVk(vk, down);
    }

    static void Unicode(string s)
    {
        List<INPUT> list = new List<INPUT>();
        foreach (char ch in s)
        {
            for (int up = 0; up < 2; up++)
            {
                INPUT i = new INPUT();
                i.type = INPUT_KEYBOARD;
                i.u.ki.wScan = ch;
                i.u.ki.dwFlags = UNICODE | (up == 1 ? KEYUP : 0);
                list.Add(i);
            }
        }
        Inject(list.ToArray(), "символ «" + s + "»");
    }

    static void KeyVk(ushort vk, bool down)
    {
        INPUT i = new INPUT();
        i.type = INPUT_KEYBOARD;
        i.u.ki.wVk = vk;
        i.u.ki.dwFlags = (down ? 0 : KEYUP) | (Extended(vk) ? EXTENDEDKEY : 0);
        if (down) keysDown.Add(vk); else keysDown.Remove(vk);
        Inject(new[] { i }, "клавиша " + vk.ToString("X2") + (down ? " нажата" : " отпущена"));
    }

    static bool Extended(ushort vk)
    {
        return (vk >= 0x21 && vk <= 0x28) || vk == 0x2D || vk == 0x2E || vk == 0xA3 || vk == 0xA5 || vk == 0x5B || vk == 0x5C || vk == 0x5D;
    }

    // Код клавиши из браузера (KeyboardEvent.code) → код Windows.
    static ushort Vk(string code)
    {
        if (code == null) return 0;
        if (code.Length == 4 && code.StartsWith("Key")) return code[3];
        if (code.Length == 6 && code.StartsWith("Digit")) return code[5];
        int n;
        if (code.Length <= 3 && code.StartsWith("F") && int.TryParse(code.Substring(1), out n) && n >= 1 && n <= 12)
            return (ushort)(0x6F + n);
        switch (code)
        {
            case "Enter": case "NumpadEnter": return 0x0D;
            case "Backspace": return 0x08;
            case "Tab": return 0x09;
            case "Escape": return 0x1B;
            case "Space": return 0x20;
            case "PageUp": return 0x21;
            case "PageDown": return 0x22;
            case "End": return 0x23;
            case "Home": return 0x24;
            case "ArrowLeft": return 0x25;
            case "ArrowUp": return 0x26;
            case "ArrowRight": return 0x27;
            case "ArrowDown": return 0x28;
            case "Insert": return 0x2D;
            case "Delete": return 0x2E;
            case "ShiftLeft": return 0xA0;
            case "ShiftRight": return 0xA1;
            case "ControlLeft": return 0xA2;
            case "ControlRight": return 0xA3;
            case "AltLeft": return 0xA4;
            case "AltRight": return 0xA5;
            case "MetaLeft": return 0x5B;
            case "MetaRight": return 0x5C;
            case "ContextMenu": return 0x5D;
            case "CapsLock": return 0x14;
            case "Minus": return 0xBD;
            case "Equal": return 0xBB;
            case "BracketLeft": return 0xDB;
            case "BracketRight": return 0xDD;
            case "Backslash": return 0xDC;
            case "Semicolon": return 0xBA;
            case "Quote": return 0xDE;
            case "Comma": return 0xBC;
            case "Period": return 0xBE;
            case "Slash": return 0xBF;
            case "Backquote": return 0xC0;
        }
        return 0;
    }

    // Связь оборвалась — отпускаем всё, что осталось зажатым, чтобы не «залипли» Shift или кнопка мыши.
    static void ReleaseAll()
    {
        lock (inputLock)
        {
            foreach (int b in new List<int>(buttonsDown))
            {
                INPUT i = new INPUT();
                i.type = INPUT_MOUSE;
                i.u.mi.dwFlags = b == 2 ? RIGHTUP : b == 1 ? MIDDLEUP : LEFTUP;
                Inject(new[] { i }, "отпустить кнопку мыши " + b);
            }
            buttonsDown.Clear();
            foreach (ushort k in new List<ushort>(keysDown)) KeyVk(k, false);
            keysDown.Clear();
        }
    }

    static void Log(string s)
    {
        if (logPath == null) return;
        try { File.AppendAllText(logPath, DateTime.Now.ToString("HH:mm:ss.fff") + " " + s + "\r\n", Encoding.UTF8); }
        catch { }
    }
}
