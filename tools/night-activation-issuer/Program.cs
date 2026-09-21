using System;
using System.Drawing;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Windows.Forms;

namespace NightActivationIssuer
{
    internal static class Program
    {
        [STAThread]
        private static void Main()
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new IssuerForm());
        }
    }

    internal sealed class IssuerForm : Form
    {
        private readonly TextBox androidId = new TextBox();
        private readonly TextBox code = new TextBox();
        private readonly Label status = new Label();

        public IssuerForm()
        {
            Text = "Night 激活码签发器";
            ClientSize = new Size(720, 430);
            MinimumSize = new Size(650, 420);
            BackColor = Color.FromArgb(7, 11, 20);
            ForeColor = Color.White;
            Font = new Font("Microsoft YaHei UI", 10F);

            var title = new Label { Text = "NIGHT / ACTIVATION ISSUER", ForeColor = Color.FromArgb(153, 211, 255), Font = new Font(Font.FontFamily, 20F, FontStyle.Bold), AutoSize = true, Left = 28, Top = 24 };
            var hint = new Label { Text = "输入目标设备 Android ID，离线签发专属激活码", ForeColor = Color.FromArgb(160, 174, 200), AutoSize = true, Left = 30, Top = 72 };
            androidId.SetBounds(30, 110, 660, 34);
            androidId.Anchor = AnchorStyles.Left | AnchorStyles.Top | AnchorStyles.Right;
            androidId.BackColor = Color.FromArgb(24, 33, 51);
            androidId.ForeColor = Color.White;
            androidId.BorderStyle = BorderStyle.FixedSingle;

            var issue = MakeButton("签发激活码", 30, 160, 210);
            issue.Click += delegate { Issue(); };
            var copy = MakeButton("复制激活码", 258, 160, 210);
            copy.Click += delegate { if (!String.IsNullOrWhiteSpace(code.Text)) Clipboard.SetText(code.Text); };
            var clear = MakeButton("清空", 480, 160, 210);
            clear.Click += delegate { androidId.Clear(); code.Clear(); status.Text = ""; };

            code.SetBounds(30, 215, 660, 120);
            code.Anchor = AnchorStyles.Left | AnchorStyles.Top | AnchorStyles.Right | AnchorStyles.Bottom;
            code.Multiline = true;
            code.ReadOnly = true;
            code.BackColor = Color.Black;
            code.ForeColor = Color.FromArgb(216, 255, 225);
            code.Font = new Font("Consolas", 10F);
            code.ScrollBars = ScrollBars.Vertical;
            status.SetBounds(30, 355, 660, 44);
            status.Anchor = AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Bottom;
            status.ForeColor = Color.FromArgb(141, 238, 213);

            Controls.AddRange(new Control[] { title, hint, androidId, issue, copy, clear, code, status });
        }

        private Button MakeButton(string text, int left, int top, int width)
        {
            return new Button { Text = text, Left = left, Top = top, Width = width, Height = 38, FlatStyle = FlatStyle.Flat, BackColor = Color.FromArgb(41, 91, 153), ForeColor = Color.White };
        }

        private void Issue()
        {
            try
            {
                string id = androidId.Text.Trim().ToLowerInvariant();
                if (id.Length == 0) throw new InvalidOperationException("Android ID 不能为空。");
                string keyPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "Night-activation-private-key.xml");
                if (!File.Exists(keyPath)) throw new FileNotFoundException("签发私钥文件不在 EXE 同目录。", keyPath);
                using (var rsa = new RSACryptoServiceProvider())
                {
                    rsa.PersistKeyInCsp = false;
                    rsa.FromXmlString(File.ReadAllText(keyPath, Encoding.UTF8));
                    byte[] payload = Encoding.UTF8.GetBytes("Night|1|" + id);
                    byte[] signature = rsa.SignData(payload, CryptoConfig.MapNameToOID("SHA256"));
                    code.Text = "N1." + Convert.ToBase64String(signature).TrimEnd('=').Replace('+', '-').Replace('/', '_');
                }
                status.Text = "签发完成；激活码仅适用于 Android ID：" + id;
            }
            catch (Exception error)
            {
                code.Text = "";
                status.Text = "签发失败：" + error.Message;
            }
        }
    }
}
