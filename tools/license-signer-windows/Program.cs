using System;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Windows.Forms;

namespace YipaSULicenseSigner
{
    internal static class Program
    {
        [STAThread]
        private static void Main()
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new SignerForm());
        }
    }

    internal sealed class SignerForm : Form
    {
        private readonly TextBox androidId = new TextBox();
        private readonly TextBox activationCode = new TextBox();

        public SignerForm()
        {
            Text = "YipaSU Android ID 签发工具";
            ClientSize = new Size(680, 355);
            MinimumSize = new Size(620, 350);
            StartPosition = FormStartPosition.CenterScreen;
            Font = new Font("Microsoft YaHei UI", 9F);
            BackColor = Color.FromArgb(246, 248, 252);

            Controls.Add(new Label
            {
                Text = "YipaSU 激活码签发工具",
                Font = new Font("Microsoft YaHei UI", 19F, FontStyle.Bold),
                ForeColor = Color.FromArgb(38, 65, 125),
                AutoSize = true,
                Location = new Point(24, 20)
            });
            Controls.Add(new Label
            {
                Text = "输入目标管理器显示的 Android ID，全程脱机签发。",
                AutoSize = true,
                ForeColor = Color.DimGray,
                Location = new Point(28, 67)
            });
            Controls.Add(new Label { Text = "Android ID", AutoSize = true, Location = new Point(28, 111) });
            androidId.SetBounds(125, 105, 520, 30);
            Controls.Add(androidId);

            var signButton = new Button { Text = "生成激活码" };
            signButton.SetBounds(125, 151, 160, 40);
            signButton.Click += (_, __) => Sign();
            Controls.Add(signButton);

            var copyButton = new Button { Text = "复制激活码" };
            copyButton.SetBounds(300, 151, 160, 40);
            copyButton.Click += (_, __) =>
            {
                if (!string.IsNullOrWhiteSpace(activationCode.Text)) Clipboard.SetText(activationCode.Text);
            };
            Controls.Add(copyButton);

            activationCode.Multiline = true;
            activationCode.ReadOnly = true;
            activationCode.ScrollBars = ScrollBars.Vertical;
            activationCode.SetBounds(28, 213, 617, 105);
            activationCode.Anchor = AnchorStyles.Top | AnchorStyles.Bottom | AnchorStyles.Left | AnchorStyles.Right;
            Controls.Add(activationCode);
        }

        private void Sign()
        {
            try
            {
                var id = androidId.Text.Trim();
                if (id.Length == 0) throw new InvalidOperationException("请输入 Android ID。");
                var keyBytes = Convert.FromBase64String(ReadPrivateKey());
                byte[] rawSignature;
                using (var key = CngKey.Import(keyBytes, CngKeyBlobFormat.Pkcs8PrivateBlob))
                using (var ecdsa = new ECDsaCng(key))
                {
                    rawSignature = ecdsa.SignData(
                        Encoding.UTF8.GetBytes("YipaSU|1|" + id),
                        HashAlgorithmName.SHA256
                    );
                }
                var derSignature = IsDer(rawSignature) ? rawSignature : P1363ToDer(rawSignature);
                activationCode.Text = "YIPASU1." + Base64Url(derSignature);
            }
            catch (Exception error)
            {
                MessageBox.Show(this, error.Message, "签发失败", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }

        private static string ReadPrivateKey()
        {
            using (var stream = Assembly.GetExecutingAssembly()
                .GetManifestResourceStream("YipaSULicenseSigner.private.key"))
            using (var reader = new StreamReader(stream ?? throw new InvalidOperationException("内置私钥缺失。")))
                return reader.ReadToEnd().Trim();
        }

        private static bool IsDer(byte[] value) => value.Length > 8 && value[0] == 0x30;

        private static byte[] P1363ToDer(byte[] value)
        {
            if (value.Length == 0 || value.Length % 2 != 0)
                throw new CryptographicException("ECDSA 签名格式无效。");
            var half = value.Length / 2;
            var r = EncodeInteger(value.Take(half).ToArray());
            var s = EncodeInteger(value.Skip(half).ToArray());
            var body = r.Concat(s).ToArray();
            if (body.Length >= 128) throw new CryptographicException("ECDSA DER 长度超限。");
            return new byte[] { 0x30, (byte)body.Length }.Concat(body).ToArray();
        }

        private static byte[] EncodeInteger(byte[] value)
        {
            var first = 0;
            while (first < value.Length - 1 && value[first] == 0) first++;
            var number = value.Skip(first).ToArray();
            if ((number[0] & 0x80) != 0) number = new byte[] { 0 }.Concat(number).ToArray();
            return new byte[] { 0x02, (byte)number.Length }.Concat(number).ToArray();
        }

        private static string Base64Url(byte[] value) => Convert.ToBase64String(value)
            .TrimEnd('=').Replace('+', '-').Replace('/', '_');
    }
}
