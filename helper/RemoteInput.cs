// Удалённая помощь: команды мыши и клавиатуры от звонилки → настоящие нажатия в Windows (SendInput).
// Общая часть «Помощника Звонилки» и программы «Звонилка» для компьютера.
//
// Команды: ["m", x, y] — мышь в долях экрана (0…1); ["d"/"u", кнопка, x, y]; ["w", ±1] — колесо;
// ["k", 1|0, code, key, модификаторы: 1 Ctrl, 2 Alt, 4 Win].

using System;
using System.Collections.Generic;
using System.Globalization;
using System.Runtime.InteropServices;

static class RemoteInput
{
    public static bool Dry;                                   // проверка: только записывать, не нажимать
    public static Action<string> Log = delegate { };
    public static DateTime LastInput = DateTime.MinValue;

    static readonly object inputLock = new object();
    static readonly HashSet<int> buttonsDown = new HashSet<int>();
    static readonly HashSet<ushort> keysDown = new HashSet<ushort>();

    public static void Handle(object[] e)
    {
        if (e == null || e.Length == 0 || e.Length > 8) return;
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

    // Связь оборвалась — отпускаем всё, что осталось зажатым, чтобы не «залипли» Shift или кнопка мыши.
    public static void ReleaseAll()
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

    static double Num(object[] e, int i)
    {
        return i < e.Length && e[i] != null ? Convert.ToDouble(e[i], CultureInfo.InvariantCulture) : 0;
    }

    static string Str(object[] e, int i)
    {
        return i < e.Length ? e[i] as string : null;
    }

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

    static void Inject(INPUT[] inputs, string what)
    {
        LastInput = DateTime.Now;
        if (Dry) { Log(what); return; }
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
}
