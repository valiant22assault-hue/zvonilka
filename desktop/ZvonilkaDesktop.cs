// Звонилка для Windows: та же звонилка с сайта, но в своём окне (WebView2 — движок Edge, встроенный в Windows).
// Крестик убирает её в трей — она остаётся на связи и всплывает при входящем звонке; запускается вместе
// с Windows; компьютер не засыпает во время звонка; удалённая помощь работает без отдельного помощника.
//
// Первый запуск устанавливает программу в %LOCALAPPDATA%\Zvonilka (без прав администратора), кладёт
// ярлыки на рабочий стол и в меню «Пуск». Новая версия ставится так же — просто запустить её.
//
// Сборка (компилятор есть в Windows, библиотеки WebView2 — из пакета Microsoft.Web.WebView2):
//   csc /target:winexe /win32icon:../helper/icon.ico /r:Microsoft.Web.WebView2.Core.dll /r:Microsoft.Web.WebView2.WinForms.dll
//       /r:System.Web.Extensions.dll /r:Microsoft.CSharp.dll /resource:<каждая библиотека>
//       ZvonilkaDesktop.cs ..\helper\RemoteInput.cs
// Для проверки: --portable (не устанавливать), --url=адрес, --selftest --dry --log=путь, --tray (запуск в трей).

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.Reflection;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;
using Microsoft.Win32;

// Сведения о программе в свойствах файла: без них Windows и антивирусы относятся к файлу подозрительнее.
[assembly: AssemblyTitle("Звонилка")]
[assembly: AssemblyDescription("Звонилка — звонки и переписка с родными")]
[assembly: AssemblyProduct("Звонилка")]
[assembly: AssemblyCompany("Mikhail Zhuravlev")]
[assembly: AssemblyCopyright("Mikhail Zhuravlev")]
[assembly: AssemblyVersion("1.0.0.0")]
[assembly: AssemblyFileVersion("1.0.0.0")]

static class Program
{
    public const string Home = "https://valiant22assault-hue.github.io/zvonilka/";
    // --dev: отдельная копия для проверки — свои данные, настройки и окно, установленной Звонилке не мешает.
    public static string Name = "Zvonilka";
    public static string AppDir { get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), Name); } }
    public static string InstalledExe { get { return Path.Combine(AppDir, "Zvonilka.exe"); } }
    const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    static string logPath;

    [STAThread]
    static void Main(string[] args)
    {
        bool portable = false, tray = false, selftest = false;
        string url = Home;
        foreach (string a in args)
        {
            if (a == "--portable") portable = true;
            else if (a == "--tray") tray = true;
            else if (a == "--selftest") selftest = true;
            else if (a == "--dev") { Name = "Zvonilka.Dev"; portable = true; }
            else if (a == "--dry") RemoteInput.Dry = true;   // удалённая помощь только записывается
            else if (a.StartsWith("--url=")) url = a.Substring(6);
            else if (a.StartsWith("--log=")) logPath = a.Substring(6);
        }
        RemoteInput.Log = Log;
        DpiAware();
        if (!portable && !SamePath(Application.ExecutablePath, InstalledExe))
        {
            Install();
            return;
        }
        bool first;
        Mutex single = new Mutex(true, @"Local\" + Name + ".Desktop", out first);
        if (!first)
        {
            // Уже запущена — просто показываем её окно.
            try { EventWaitHandle.OpenExisting(@"Local\" + Name + ".Show").Set(); } catch { }
            return;
        }
        Run(url, tray, selftest);
        GC.KeepAlive(single);
    }

    // Отдельный метод: типы WebView2 загружаются только здесь — установщику они не нужны.
    [MethodImpl(MethodImplOptions.NoInlining)]
    static void Run(string url, bool tray, bool selftest)
    {
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        Application.Run(new MainForm(url, tray, selftest));
    }

    // ---------- установка ----------

    static void Install()
    {
        try
        {
            Directory.CreateDirectory(AppDir);
            // Запущена прежняя версия — просим её закрыться, чтобы заменить файл.
            try
            {
                EventWaitHandle.OpenExisting(@"Local\" + Name + ".Quit").Set();
                Thread.Sleep(2000);
            }
            catch { }
            for (int i = 0; ; i++)
            {
                try { File.Copy(Application.ExecutablePath, InstalledExe, true); break; }
                catch (IOException) { if (i >= 10) throw; Thread.Sleep(500); }
            }
            foreach (string name in new[] { "Microsoft.Web.WebView2.Core.dll", "Microsoft.Web.WebView2.WinForms.dll" })
                Extract(name, name);
            Extract("WebView2Loader." + Arch() + ".dll", "WebView2Loader.dll");
            Shortcut(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), "Звонилка.lnk"));
            Shortcut(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "Звонилка.lnk"));
            // Запуск вместе с Windows — по умолчанию; если его выключили в меню значка, не включаем снова.
            if (Setting("autostartChosen") == null) SetAutostart(true);
            Process.Start(InstalledExe);
        }
        catch (Exception e)
        {
            MessageBox.Show("Не получилось установить Звонилку: " + e.Message, "Звонилка", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    static void Extract(string resource, string fileName)
    {
        using (Stream s = typeof(Program).Assembly.GetManifestResourceStream(resource))
        {
            if (s == null) throw new FileNotFoundException("нет файла " + resource + " внутри программы");
            using (FileStream f = File.Create(Path.Combine(AppDir, fileName))) s.CopyTo(f);
        }
    }

    static string Arch()
    {
        string a = Environment.GetEnvironmentVariable("PROCESSOR_ARCHITECTURE") ?? "";
        return a.Equals("ARM64", StringComparison.OrdinalIgnoreCase) ? "arm64" : Environment.Is64BitProcess ? "x64" : "x86";
    }

    static void Shortcut(string path)
    {
        try
        {
            dynamic shell = Activator.CreateInstance(Type.GetTypeFromProgID("WScript.Shell"));
            dynamic link = shell.CreateShortcut(path);
            link.TargetPath = InstalledExe;
            link.WorkingDirectory = AppDir;
            link.IconLocation = InstalledExe + ",0";
            link.Description = "Звонилка — звонки и переписка с родными";
            link.Save();
        }
        catch (Exception e) { Log("ярлык: " + e.Message); }
    }

    public static bool Autostart()
    {
        using (RegistryKey k = Registry.CurrentUser.OpenSubKey(RunKey))
            return k != null && k.GetValue(Name) != null;
    }

    public static void SetAutostart(bool on)
    {
        using (RegistryKey k = Registry.CurrentUser.CreateSubKey(RunKey))
        {
            if (on) k.SetValue(Name, "\"" + InstalledExe + "\" --tray");
            else k.DeleteValue(Name, false);
        }
        SetSetting("autostartChosen", "1");
    }

    public static string Setting(string name)
    {
        using (RegistryKey k = Registry.CurrentUser.OpenSubKey(@"Software\" + Name))
            return k == null ? null : k.GetValue(name) as string;
    }

    public static void SetSetting(string name, string value)
    {
        using (RegistryKey k = Registry.CurrentUser.CreateSubKey(@"Software\" + Name)) k.SetValue(name, value);
    }

    static bool SamePath(string a, string b)
    {
        return string.Equals(Path.GetFullPath(a), Path.GetFullPath(b), StringComparison.OrdinalIgnoreCase);
    }

    public static void Log(string s)
    {
        if (logPath == null) return;
        try { File.AppendAllText(logPath, DateTime.Now.ToString("HH:mm:ss.fff") + " " + s + "\r\n", Encoding.UTF8); }
        catch { }
    }

    // Чёткий текст на экранах с увеличением: иначе Windows растягивает окно картинкой и оно мылится.
    [DllImport("user32.dll")] static extern bool SetProcessDpiAwarenessContext(IntPtr value);
    [DllImport("user32.dll")] static extern bool SetProcessDPIAware();

    static void DpiAware()
    {
        try { if (SetProcessDpiAwarenessContext(new IntPtr(-4))) return; } catch { }
        try { SetProcessDPIAware(); } catch { }
    }
}

class MainForm : Form
{
    readonly string startUrl, origin;
    readonly bool selftest;
    readonly WebView2 web = new WebView2();
    readonly NotifyIcon tray = new NotifyIcon();
    readonly ToolStripMenuItem autostartItem = new ToolStripMenuItem("Запускать вместе с Windows");
    readonly JavaScriptSerializer json = new JavaScriptSerializer();
    readonly CaptionBar caption;
    readonly float scale;
    CoreWebView2Controller controller;
    bool allowShow, quitting, keepNormalSize;
    Size normalSize;

    int Px(double v) { return (int)Math.Round(v * scale); }

    public MainForm(string url, bool startInTray, bool selftest)
    {
        startUrl = url;
        origin = new Uri(url).GetLeftPart(UriPartial.Authority);
        this.selftest = selftest;
        allowShow = !startInTray;

        Text = "Звонилка";
        try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }
        BackColor = CaptionBar.Back;
        using (Graphics g = CreateGraphics()) scale = g.DpiX / 96f;
        MinimumSize = new Size(Px(360), Px(480));
        // Как окно приложения из Chrome: открывается там же и такого же размера, как его оставили.
        StartPosition = FormStartPosition.Manual;
        bool maximized;
        Rectangle saved = SavedBounds(out maximized);
        Bounds = saved.IsEmpty ? DefaultBounds() : saved;
        normalSize = Size;
        if (maximized) WindowState = FormWindowState.Maximized;
        Resize += (s, e) => TrackNormalSize();

        caption = new CaptionBar(this, scale);
        web.Dock = DockStyle.Fill;
        web.DefaultBackgroundColor = BackColor;
        Controls.Add(caption);
        Controls.Add(web);
        web.BringToFront();   // заголовок сверху, страница — на всё остальное
        Activated += (s, e) => caption.Invalidate();
        Deactivate += (s, e) => caption.Invalidate();
        Resize += (s, e) => caption.Invalidate();
        // Страница должна знать, что окно спрятано или свёрнуто: тогда новое сообщение — непрочитанное и с уведомлением.
        VisibleChanged += (s, e) => SyncVisible();
        Resize += (s, e) => SyncVisible();

        ContextMenuStrip menu = new ContextMenuStrip();
        ToolStripMenuItem open = new ToolStripMenuItem("Открыть Звонилку", null, (s, e) => ShowApp());
        open.Font = new Font(open.Font, FontStyle.Bold);
        autostartItem.CheckOnClick = true;
        autostartItem.Checked = Program.Autostart();
        autostartItem.CheckedChanged += (s, e) => Program.SetAutostart(autostartItem.Checked);
        menu.Items.Add(open);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(autostartItem);
        menu.Items.Add(new ToolStripMenuItem("Выход", null, (s, e) => Quit()));
        tray.Icon = Icon;
        tray.Text = "Звонилка";
        tray.ContextMenuStrip = menu;
        tray.Visible = true;
        tray.MouseClick += (s, e) => { if (e.Button == MouseButtons.Left) ShowApp(); };
        tray.BalloonTipClicked += (s, e) => ShowApp();

        FormClosing += OnClosing;
        // Повторный запуск показывает это окно, новая версия при установке просит его закрыться.
        Signal(@"Local\" + Program.Name + ".Show", ShowApp);
        Signal(@"Local\" + Program.Name + ".Quit", Quit);

        var unused = web.Handle;   // окно может быть скрыто (запуск в трей), а WebView2 нужен готовый элемент
        BeginInvoke((Action)StartWeb);
    }

    void Signal(string name, Action action)
    {
        EventWaitHandle ev = new EventWaitHandle(false, EventResetMode.AutoReset, name);
        ThreadPool.RegisterWaitForSingleObject(ev, (state, timedOut) =>
        {
            try { BeginInvoke(action); } catch { }
        }, null, -1, false);
    }

    // Запуск в трей: окно не показываем, пока не попросят.
    protected override void SetVisibleCore(bool value)
    {
        if (!allowShow)
        {
            value = false;
            if (!IsHandleCreated) CreateHandle();
        }
        base.SetVisibleCore(value);
    }

    async void StartWeb()
    {
        try
        {
            // Без притормаживания в фоне: звонилка в трее должна оставаться на связи и звонить
            // (и звонить вслух, даже если после запуска с Windows в окно ещё ни разу не нажимали).
            CoreWebView2EnvironmentOptions opts = new CoreWebView2EnvironmentOptions(
                "--autoplay-policy=no-user-gesture-required " +
                "--disable-background-timer-throttling --disable-renderer-backgrounding " +
                "--disable-backgrounding-occluded-windows --disable-features=CalculateNativeWinOcclusion,IntensiveWakeUpThrottling");
            opts.Language = "ru";
            CoreWebView2Environment env = await CoreWebView2Environment.CreateAsync(null, Path.Combine(Program.AppDir, "Data"), opts);
            await web.EnsureCoreWebView2Async(env);
        }
        catch (WebView2RuntimeNotFoundException)
        {
            if (MessageBox.Show("Для Звонилки нужен компонент Microsoft Edge WebView2. Открыть страницу загрузки?",
                    "Звонилка", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) == DialogResult.Yes)
                OpenExternal("https://go.microsoft.com/fwlink/p/?LinkId=2124703");
            Quit();
            return;
        }
        catch (Exception e)
        {
            Program.Log("WebView2: " + e);
            MessageBox.Show("Не получилось запустить Звонилку: " + e.Message, "Звонилка", MessageBoxButtons.OK, MessageBoxIcon.Error);
            Quit();
            return;
        }
        // Элемент WinForms не говорит странице, что окно спрятано в трей, — делаем это сами через его CoreWebView2Controller.
        FieldInfo field = typeof(WebView2).GetField("_coreWebView2Controller", BindingFlags.Instance | BindingFlags.NonPublic);
        if (field != null) controller = field.GetValue(web) as CoreWebView2Controller;
        SyncVisible();
        CoreWebView2 core = web.CoreWebView2;
        core.Settings.AreDevToolsEnabled = false;
        core.Settings.IsStatusBarEnabled = false;
        core.PermissionRequested += (s, e) =>
        {
            // Микрофон, камера, показ экрана — только самой звонилке.
            e.State = Ours(e.Uri) ? CoreWebView2PermissionState.Allow : CoreWebView2PermissionState.Deny;
        };
        core.NewWindowRequested += (s, e) => { e.Handled = true; OpenExternal(e.Uri); };
        core.NavigationStarting += (s, e) =>
        {
            if (Ours(e.Uri) || e.Uri.StartsWith("about:")) return;
            e.Cancel = true;   // чужие ссылки из переписки — в обычном браузере, звонилка остаётся на месте
            OpenExternal(e.Uri);
        };
        core.WebMessageReceived += OnMessage;
        core.ProcessFailed += (s, e) =>
        {
            Program.Log("сбой страницы: " + e.ProcessFailedKind);
            BeginInvoke((Action)(() => { try { web.Reload(); } catch { } }));
        };
        if (selftest) core.NavigationCompleted += (s, e) => SelfTest();
        web.Source = new Uri(startUrl);
    }

    bool Ours(string uri)
    {
        return uri != null && (uri == origin || uri.StartsWith(origin + "/", StringComparison.OrdinalIgnoreCase));
    }

    // ---------- сообщения от страницы ----------

    void OnMessage(object sender, CoreWebView2WebMessageReceivedEventArgs e)
    {
        if (!Ours(e.Source)) return;
        Dictionary<string, object> d;
        try { d = json.DeserializeObject(e.WebMessageAsJson) as Dictionary<string, object>; }
        catch { return; }
        if (d == null) return;
        string t = Get(d, "t"), name = Get(d, "name");
        switch (t)
        {
            case "ring":      // входящий звонок — окно на передний план
                ShowApp();
                Flash(true);
                Balloon(name + " звонит", "Откройте Звонилку, чтобы ответить.");
                break;
            case "ringEnd":
                Flash(false);
                break;
            case "missed":
                Balloon("Пропущенный звонок", name);
                break;
            case "msg":
                if (!Visible || !ContainsFocus) Balloon("Сообщение от " + name, Get(d, "text"));
                break;
            case "call":      // во время разговора компьютер не засыпает
                bool on = d.ContainsKey("on") && d["on"] is bool && (bool)d["on"];
                SetThreadExecutionState(on ? ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED : ES_CONTINUOUS);
                break;
            case "input":     // удалённая помощь: собеседник управляет мышью и клавиатурой
                RemoteInput.Handle(d.ContainsKey("e") ? d["e"] as object[] : null);
                break;
            case "inputStop":
                RemoteInput.ReleaseAll();
                break;
        }
    }

    static string Get(Dictionary<string, object> d, string key)
    {
        object v;
        return d.TryGetValue(key, out v) && v != null ? v.ToString() : "";
    }

    // ---------- окно и трей ----------

    // Размер при первом запуске — как у нового окна Chrome: почти во всю высоту, шириной до 1050 точек
    // (на широком экране — до половины), с отступом 10 точек от края.
    Rectangle DefaultBounds()
    {
        Rectangle work = Screen.PrimaryScreen.WorkingArea;
        int tile = Px(10);
        int w = Math.Min(work.Width - 2 * tile, Px(1050));
        if ((double)work.Width / work.Height > 1.6 && work.Width > Px(1600)) w = Math.Max(w, work.Width / 2 - Px(15));
        return new Rectangle(work.X + tile, work.Y + tile, w, work.Height - 2 * tile);
    }

    Rectangle SavedBounds(out bool maximized)
    {
        maximized = false;
        string[] v = (Program.Setting("window") ?? "").Split(',');
        int x, y, w, h;
        if (v.Length != 5 || !int.TryParse(v[0], out x) || !int.TryParse(v[1], out y) ||
            !int.TryParse(v[2], out w) || !int.TryParse(v[3], out h)) return Rectangle.Empty;
        Rectangle r = new Rectangle(x, y, Math.Max(w, MinimumSize.Width), Math.Max(h, MinimumSize.Height));
        // Монитор могли отключить или поменять разрешение — тогда открываемся как в первый раз.
        foreach (Screen s in Screen.AllScreens)
        {
            Rectangle i = Rectangle.Intersect(s.WorkingArea, r);
            if (i.Width >= Px(200) && i.Height >= Px(100))
            {
                maximized = v[4] == "1";
                return r;
            }
        }
        return Rectangle.Empty;
    }

    void SaveBounds()
    {
        if (!allowShow) return;   // окно ещё ни разу не показывали
        Rectangle b = WindowState == FormWindowState.Normal ? Bounds : RestoreBounds;
        Program.SetSetting("window", b.X + "," + b.Y + "," + b.Width + "," + b.Height + "," +
            (WindowState == FormWindowState.Maximized ? "1" : "0"));
    }

    // ---------- свой заголовок окна ----------
    // Системный заголовок убираем (рамки по бокам и снизу остаются — за них тянут), вместо него — CaptionBar.

    const int WM_WINDOWPOSCHANGING = 0x46, WM_NCCALCSIZE = 0x83, WM_NCHITTEST = 0x84;
    const int HTCLIENT = 1, HTCAPTION = 2, HTTOP = 12, HTTOPLEFT = 13, HTTOPRIGHT = 14;
    const int SW_RESTORE = 9;

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        // Тёмная тема для того, что рисует сама Windows (рамка, меню окна): 20 — Windows 10 2004 и новее, 19 — раньше.
        int on = 1;
        try { if (DwmSetWindowAttribute(Handle, 20, ref on, 4) != 0) DwmSetWindowAttribute(Handle, 19, ref on, 4); } catch { }
        SetWindowPos(Handle, IntPtr.Zero, 0, 0, 0, 0, 0x0001 | 0x0002 | 0x0004 | 0x0010 | 0x0020);   // пересчитать рамку
    }

    protected override void WndProc(ref Message m)
    {
        if (m.Msg == WM_NCCALCSIZE && m.WParam != IntPtr.Zero)
        {
            RECT r = (RECT)Marshal.PtrToStructure(m.LParam, typeof(RECT));
            int f = FrameSize();
            r.Left += f;
            r.Right -= f;
            r.Bottom -= f;
            if (IsZoomed(Handle)) r.Top += f;   // развёрнутое окно заходит за край экрана на ширину рамки
            Marshal.StructureToPtr(r, m.LParam, false);
            m.Result = IntPtr.Zero;
            return;
        }
        if (m.Msg == WM_WINDOWPOSCHANGING) KeepNormalSize(ref m);
        base.WndProc(ref m);
        if (m.Msg == WM_NCHITTEST && (int)m.Result == HTCLIENT)
        {
            // Сюда доходит только полоса заголовка: страница и кнопки окна отвечают сами.
            Point p = PointToClient(new Point(unchecked((short)(long)m.LParam), unchecked((short)((long)m.LParam >> 16))));
            if (WindowState == FormWindowState.Normal && p.Y < Px(5))
                m.Result = (IntPtr)(p.X < Px(16) ? HTTOPLEFT : p.X >= ClientSize.Width - Px(16) ? HTTOPRIGHT : HTTOP);
            else if (caption != null && p.Y < caption.Height)
                m.Result = (IntPtr)HTCAPTION;   // тянуть окно, двойной щелчок, прилипание к краям — как у обычного заголовка
        }
    }

    // После «развернуть → вернуть» (и после сворачивания) WinForms пересчитывает размер окна с системным
    // заголовком, которого у нас нет, — и окно каждый раз вырастает. Пока окно возвращается, держим прежний размер.
    void TrackNormalSize()
    {
        if (WindowState != FormWindowState.Normal) { keepNormalSize = true; return; }
        normalSize = Size;
        if (keepNormalSize) BeginInvoke((Action)(() => { keepNormalSize = false; }));
    }

    void KeepNormalSize(ref Message m)
    {
        if (!keepNormalSize || IsZoomed(Handle) || IsIconic(Handle)) return;
        WINDOWPOS wp = (WINDOWPOS)Marshal.PtrToStructure(m.LParam, typeof(WINDOWPOS));
        if ((wp.flags & 0x0001) != 0 || (wp.cx == normalSize.Width && wp.cy == normalSize.Height)) return;   // SWP_NOSIZE
        wp.cx = normalSize.Width;
        wp.cy = normalSize.Height;
        Marshal.StructureToPtr(wp, m.LParam, false);
    }

    int FrameSize()
    {
        if (IsHandleCreated)
        {
            try
            {
                uint dpi = GetDpiForWindow(Handle);
                return GetSystemMetricsForDpi(SM_CXSIZEFRAME, dpi) + GetSystemMetricsForDpi(SM_CXPADDEDBORDER, dpi);
            }
            catch (EntryPointNotFoundException) { }   // Windows старше 10 (1607)
        }
        return GetSystemMetrics(SM_CXSIZEFRAME) + GetSystemMetrics(SM_CXPADDEDBORDER);
    }

    const int SM_CXSIZEFRAME = 32, SM_CXPADDEDBORDER = 92;
    [StructLayout(LayoutKind.Sequential)] struct RECT { public int Left, Top, Right, Bottom; }
    [StructLayout(LayoutKind.Sequential)] struct WINDOWPOS { public IntPtr hwnd, after; public int x, y, cx, cy; public uint flags; }
    [DllImport("user32.dll")] static extern bool IsZoomed(IntPtr hwnd);
    [DllImport("user32.dll")] static extern bool IsIconic(IntPtr hwnd);
    [DllImport("user32.dll")] static extern bool ShowWindow(IntPtr hwnd, int cmd);
    [DllImport("user32.dll")] static extern bool SetWindowPos(IntPtr hwnd, IntPtr after, int x, int y, int cx, int cy, uint flags);
    [DllImport("user32.dll")] static extern int GetSystemMetrics(int index);
    [DllImport("user32.dll")] static extern int GetSystemMetricsForDpi(int index, uint dpi);
    [DllImport("user32.dll")] static extern uint GetDpiForWindow(IntPtr hwnd);
    [DllImport("dwmapi.dll")] static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

    void OnClosing(object sender, FormClosingEventArgs e)
    {
        SaveBounds();
        if (quitting || e.CloseReason == CloseReason.WindowsShutDown || e.CloseReason == CloseReason.TaskManagerClosing)
        {
            tray.Visible = false;   // выключение Windows или «Снять задачу» — значок у часов не должен остаться
            return;
        }
        e.Cancel = true;   // крестик — в трей, звонилка остаётся на связи
        Hide();
        if (Program.Setting("trayHintShown") == null)
        {
            Program.SetSetting("trayHintShown", "1");
            Balloon("Звонилка работает в фоне", "Звонки и сообщения будут приходить. Открыть — значок у часов, выйти — правой кнопкой → «Выход».");
        }
    }

    void SyncVisible()
    {
        if (controller == null) return;
        try { controller.IsVisible = Visible && WindowState != FormWindowState.Minimized; }
        catch { }
    }

    void ShowApp()
    {
        allowShow = true;
        Show();
        if (WindowState == FormWindowState.Minimized) ShowWindow(Handle, SW_RESTORE);   // каким было до сворачивания
        TopMost = true;    // поверх остальных окон хотя бы на миг — Windows не всегда даёт сразу перехватить фокус
        TopMost = false;
        Activate();
    }

    void Quit()
    {
        if (Visible) SaveBounds();
        quitting = true;
        tray.Visible = false;
        RemoteInput.ReleaseAll();
        SetThreadExecutionState(ES_CONTINUOUS);
        Application.Exit();
    }

    void Balloon(string title, string text)
    {
        tray.ShowBalloonTip(6000, title, string.IsNullOrEmpty(text) ? " " : text, ToolTipIcon.Info);
    }

    static void OpenExternal(string uri)
    {
        if (uri == null || !(uri.StartsWith("https://") || uri.StartsWith("http://") || uri.StartsWith("mailto:") || uri.StartsWith("tel:"))) return;
        try { Process.Start(uri); } catch { }
    }

    [StructLayout(LayoutKind.Sequential)]
    struct FLASHWINFO { public uint cbSize; public IntPtr hwnd; public uint dwFlags, uCount, dwTimeout; }
    [DllImport("user32.dll")] static extern bool FlashWindowEx(ref FLASHWINFO info);
    [DllImport("kernel32.dll")] static extern uint SetThreadExecutionState(uint flags);
    const uint ES_CONTINUOUS = 0x80000000, ES_SYSTEM_REQUIRED = 0x1, ES_DISPLAY_REQUIRED = 0x2;

    // Мигает значок на панели задач, пока звонят (или перестаёт).
    void Flash(bool on)
    {
        FLASHWINFO f = new FLASHWINFO();
        f.cbSize = (uint)Marshal.SizeOf(typeof(FLASHWINFO));
        f.hwnd = Handle;
        f.dwFlags = on ? 3u | 0xCu : 0u;   // окно и кнопка, пока не откроют; 0 — перестать
        FlashWindowEx(ref f);
    }

    // ---------- самопроверка (--selftest) ----------

    async void SelfTest()
    {
        await Task.Delay(4000);
        Program.Log("страница: " + await web.CoreWebView2.ExecuteScriptAsync(
            "JSON.stringify({ title: document.title, desk: typeof Desk !== 'undefined' && !!Desk, " +
            "screenShare: typeof navigator.mediaDevices?.getDisplayMedia, relays: typeof Relay !== 'undefined' })"));
        await web.CoreWebView2.ExecuteScriptAsync("window.zvTicks = 0; setInterval(() => window.zvTicks++, 500)");
        Close();   // как крестик: должно уйти в трей
        Program.Log("после крестика окно видно: " + Visible + ", в трее: " + tray.Visible +
            ", страница знает: " + (controller != null && !controller.IsVisible));
        await web.CoreWebView2.ExecuteScriptAsync("window.zvAudio = new AudioContext()");
        await Task.Delay(6000);
        // В трее таймеры страницы не должны засыпать (за 6 секунд — около 12 тиков), а звук — играть без нажатий.
        Program.Log("в трее страница жива: " + await web.CoreWebView2.ExecuteScriptAsync(
            "JSON.stringify({ visibility: document.visibilityState, ticks: window.zvTicks, audio: window.zvAudio.state })"));
        await web.CoreWebView2.ExecuteScriptAsync("window.chrome.webview.postMessage({ t: 'input', e: ['m', 0.5, 0.5] })");
        ShowApp();
        await Task.Delay(1000);
        Program.Log("окно снова открыто: " + await web.CoreWebView2.ExecuteScriptAsync("document.visibilityState"));
        Program.Log("проверка закончена");
        Quit();
    }
}

// Заголовок окна в цвет звонилки, как у приложений из Chrome: значок, название и кнопки окна.
class CaptionBar : Control
{
    public static readonly Color Back = Color.FromArgb(90, 94, 102);   // как theme-color страницы
    static readonly Color Edge = Color.FromArgb(80, 84, 92);           // тонкая полоска по верхнему краю
    static readonly Color Dim = Color.FromArgb(170, 174, 181);         // текст и значки неактивного окна
    static readonly string[] Names = { "Свернуть", "Развернуть", "Закрыть" };
    readonly Form form;
    readonly float scale;
    readonly Icon small;
    readonly ToolTip tip = new ToolTip();
    int hot = -1, pressed = -1;   // кнопка под мышью и нажатая: 0 — свернуть, 1 — развернуть, 2 — закрыть

    public CaptionBar(Form form, float scale)
    {
        this.form = form;
        this.scale = scale;
        SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer |
                 ControlStyles.UserPaint | ControlStyles.ResizeRedraw, true);
        SetStyle(ControlStyles.Selectable, false);
        Dock = DockStyle.Top;
        Height = Px(33);
        BackColor = Back;
        Font = new Font("Segoe UI", 9f);
        try { small = new Icon(form.Icon, Px(16), Px(16)); } catch { }
    }

    int Px(double v) { return (int)Math.Round(v * scale); }

    Rectangle ButtonRect(int i)
    {
        int w = Px(46);
        return new Rectangle(Width - (3 - i) * w, 0, w, Height);
    }

    int ButtonAt(Point p)
    {
        for (int i = 0; i < 3; i++) if (ButtonRect(i).Contains(p)) return i;
        return -1;
    }

    // Мимо кнопок — «прозрачно»: тогда окно само решает, тянуть его за заголовок или растягивать за верхний край.
    protected override void WndProc(ref Message m)
    {
        if (m.Msg == 0x84)   // WM_NCHITTEST
        {
            Point p = PointToClient(new Point(unchecked((short)(long)m.LParam), unchecked((short)((long)m.LParam >> 16))));
            bool edge = form.WindowState == FormWindowState.Normal && p.Y < Px(5);
            if (edge || ButtonAt(p) < 0) { m.Result = (IntPtr)(-1); return; }   // HTTRANSPARENT
        }
        base.WndProc(ref m);
    }

    void SetHot(int b)
    {
        if (b == hot) return;
        hot = b;
        tip.SetToolTip(this, b < 0 ? null : b == 1 && form.WindowState == FormWindowState.Maximized ? "Свернуть в окно" : Names[b]);
        Invalidate();
    }

    protected override void OnMouseMove(MouseEventArgs e) { SetHot(ButtonAt(e.Location)); }
    protected override void OnMouseLeave(EventArgs e) { SetHot(-1); }

    protected override void OnMouseDown(MouseEventArgs e)
    {
        if (e.Button != MouseButtons.Left) return;
        pressed = ButtonAt(e.Location);
        Invalidate();
    }

    protected override void OnMouseUp(MouseEventArgs e)
    {
        if (e.Button != MouseButtons.Left) return;
        int was = pressed;
        pressed = -1;
        if (was < 0 || ButtonAt(e.Location) != was) { Invalidate(); return; }
        SetHot(-1);
        if (was == 0) form.WindowState = FormWindowState.Minimized;
        else if (was == 1) form.WindowState = form.WindowState == FormWindowState.Maximized ? FormWindowState.Normal : FormWindowState.Maximized;
        else form.Close();
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        Graphics g = e.Graphics;
        bool active = Form.ActiveForm == form;
        Color fg = active ? Color.White : Dim;
        g.Clear(Back);
        if (form.WindowState == FormWindowState.Normal)
            using (SolidBrush b = new SolidBrush(Edge)) g.FillRectangle(b, 0, 0, Width, Math.Max(1, Px(1)));
        int icon = Px(16);
        if (small != null) g.DrawIcon(small, new Rectangle(Px(8), (Height - icon) / 2, icon, icon));
        int left = Px(30);
        TextRenderer.DrawText(g, form.Text, Font, new Rectangle(left, 0, Math.Max(0, ButtonRect(0).X - left), Height), fg,
            TextFormatFlags.Left | TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis | TextFormatFlags.NoPrefix);
        for (int i = 0; i < 3; i++)
        {
            Rectangle r = ButtonRect(i);
            bool down = pressed == i, over = hot == i;
            Color glyph = fg;
            if (i == 2 && (down || over))
            {
                using (SolidBrush b = new SolidBrush(down ? Color.FromArgb(241, 112, 122) : Color.FromArgb(232, 17, 35))) g.FillRectangle(b, r);
                glyph = Color.White;
            }
            else if (down || over)
                using (SolidBrush b = new SolidBrush(Color.FromArgb(down ? 50 : 28, Color.White))) g.FillRectangle(b, r);
            Glyph(g, i, r, glyph);
        }
    }

    // Значки кнопок — тонкими линиями, как у Windows 10.
    void Glyph(Graphics g, int i, Rectangle r, Color c)
    {
        int s = Px(10), d = Px(2);
        int x = r.X + (r.Width - s) / 2, y = r.Y + (r.Height - s) / 2;
        using (Pen pen = new Pen(c, Math.Max(1, (int)scale)))
        {
            if (i == 0)
                g.DrawLine(pen, x, y + s / 2, x + s - 1, y + s / 2);
            else if (i == 1 && form.WindowState == FormWindowState.Maximized)
            {
                g.DrawRectangle(pen, x, y + d, s - d - 1, s - d - 1);
                g.DrawLines(pen, new[] { new Point(x + d, y + d), new Point(x + d, y), new Point(x + s - 1, y),
                                         new Point(x + s - 1, y + s - d - 1), new Point(x + s - d - 1, y + s - d - 1) });
            }
            else if (i == 1)
                g.DrawRectangle(pen, x, y, s - 1, s - 1);
            else
            {
                g.SmoothingMode = SmoothingMode.AntiAlias;
                g.DrawLine(pen, x, y, x + s, y + s);
                g.DrawLine(pen, x + s, y, x, y + s);
                g.SmoothingMode = SmoothingMode.None;
            }
        }
    }
}
