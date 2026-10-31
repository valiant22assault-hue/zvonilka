// Помощник Звонилки — удалённая помощь на компьютере с Windows.
//
// Пока окно открыто, звонилка в браузере этого компьютера может передавать сюда движения мыши и нажатия
// клавиш собеседника — и только после того, как в звонке нажали «Разрешить». Сам помощник в интернет
// не ходит: он слушает только этот компьютер (127.0.0.1) и принимает только страницу звонилки с сайта.
//
// Сборка компилятором, который есть в Windows (.NET Framework 4):
//   csc /target:winexe /r:System.Web.Extensions.dll /win32icon:icon.ico ZvonilkaHelper.cs RemoteInput.cs
// Для проверки без настоящих нажатий: --dry (только записывать), --dev (пускать localhost), --headless, --log=путь.

using System;
using System.Collections.Generic;
using System.Drawing;
using System.Globalization;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;
using System.Windows.Forms;

[assembly: AssemblyTitle("Помощник Звонилки")]
[assembly: AssemblyDescription("Удалённая помощь для Звонилки")]
[assembly: AssemblyProduct("Звонилка")]
[assembly: AssemblyCompany("Mikhail Zhuravlev")]
[assembly: AssemblyCopyright("Mikhail Zhuravlev")]
[assembly: AssemblyVersion("1.0.0.0")]
[assembly: AssemblyFileVersion("1.0.0.0")]

static class ZvonilkaHelper
{
    const int Port = 47231;   // тот же номер — в index.html (HELPER_URL)
    static readonly string[] Origins = { "https://valiant22assault-hue.github.io" };
    static readonly string[] DevOrigins = { "http://localhost:8765", "http://127.0.0.1:8765" };

    // Помощник подключается не сразу, а когда в звонке нажмут «Разрешить», — до этого он просто ждёт.
    const string Waiting = "Готов. Подключится, когда в звонке нажмут «Управлять» и вы ответите «Разрешить».";
    static bool dev, headless;
    static string logPath;
    static Form form;
    static Label status;
    static volatile bool closing, connected;
    static TcpClient current;
    static readonly object clientLock = new object();

    [STAThread]
    static void Main(string[] args)
    {
        foreach (string a in args)
        {
            if (a == "--dev") dev = true;
            else if (a == "--dry") RemoteInput.Dry = true;
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
        RemoteInput.Log = Log;
        Log("запущен" + (RemoteInput.Dry ? " (проверка: без настоящих нажатий)" : ""));
        Thread server = new Thread(() => Serve(listener));
        server.IsBackground = true;
        server.Start();
        if (headless) { server.Join(); return; }

        Application.EnableVisualStyles();
        form = BuildForm();
        Application.Run(form);
        closing = true;
        RemoteInput.ReleaseAll();
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
            bool busy = (DateTime.Now - RemoteInput.LastInput).TotalSeconds < 2;
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
            RemoteInput.ReleaseAll();
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
    // Сами нажатия — в RemoteInput.cs (общий с программой «Звонилка» для компьютера).

    static readonly JavaScriptSerializer json = new JavaScriptSerializer();

    static void OnMessage(string text)
    {
        object[] e;
        try { e = json.DeserializeObject(text) as object[]; }
        catch { return; }
        RemoteInput.Handle(e);
    }

    static void Log(string s)
    {
        if (logPath == null) return;
        try { File.AppendAllText(logPath, DateTime.Now.ToString("HH:mm:ss.fff") + " " + s + "\r\n", Encoding.UTF8); }
        catch { }
    }
}
