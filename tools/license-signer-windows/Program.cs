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
            BackColor = Color.FromArgb(10, 15, 26);
            ForeColor = Color.FromArgb(220, 240, 248);

            Controls.Add(new Label
            {
                Text = "YipaSU 激活码签发工具",
                Font = new Font("Microsoft YaHei UI", 19F, FontStyle.Bold),
                ForeColor = Color.FromArgb(55, 230, 255),
                AutoSize = true,
                Location = new Point(24, 20)
            });
            Controls.Add(new Label
            {
                Text = "输入目标管理器显示的 Android ID，全程脱机签发。",
                AutoSize = true,
                ForeColor = Color.FromArgb(134, 161, 181),
                Location = new Point(28, 67)
            });
            Controls.Add(new Label { Text = "Android ID", AutoSize = true, Location = new Point(28, 111) });
            androidId.SetBounds(125, 105, 520, 30);
            Controls.Add(androidId);

            var signButton = new Button { Text = "生成激活码" };
            signButton.SetBounds(125, 151, 160, 40);
            signButton.BackColor = Color.FromArgb(43, 105, 215);
            signButton.ForeColor = Color.White;
            signButton.FlatStyle = FlatStyle.Flat;
            signButton.Click += (_, __) => Sign();
            Controls.Add(signButton);

            var pasteButton = new Button { Text = "粘贴 Android ID" };
            pasteButton.SetBounds(300, 151, 160, 40);
            pasteButton.Click += (_, __) => { if (Clipboard.ContainsText()) androidId.Text = Clipboard.GetText().Trim(); };
            Controls.Add(pasteButton);

            var copyButton = new Button { Text = "复制激活码" };
            copyButton.SetBounds(475, 151, 170, 40);
            copyButton.Click += (_, __) =>
            {
                if (!string.IsNullOrWhiteSpace(activationCode.Text)) Clipboard.SetText(activationCode.Text);
            };
            Controls.Add(copyButton);

            activationCode.Multiline = true;
            activationCode.ReadOnly = true;
            activationCode.ScrollBars = ScrollBars.Vertical;
            activationCode.BackColor = Color.FromArgb(9, 16, 27);
            activationCode.ForeColor = Color.FromArgb(125, 255, 178);
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
                using (var key = ImportPrivateKey(keyBytes))
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

        private static CngKey ImportPrivateKey(byte[] pkcs8)
        {
            try
            {
                return CngKey.Import(pkcs8, CngKeyBlobFormat.Pkcs8PrivateBlob);
            }
            catch (CryptographicException)
            {
                // Some Windows/.NET Framework combinations expose the PKCS#8
                // blob type but fail to import an EC key. Convert the embedded
                // P-256 key to the native BCRYPT_ECCPRIVATE_BLOB layout.
                var privateOffset = -1;
                for (var i = 0; i <= pkcs8.Length - 37; i++)
                {
                    if (pkcs8[i] == 0x02 && pkcs8[i + 1] == 0x01 && pkcs8[i + 2] == 0x01 &&
                        pkcs8[i + 3] == 0x04 && pkcs8[i + 4] == 0x20)
                    {
                        privateOffset = i + 5;
                        break;
                    }
                }

                var pointOffset = pkcs8.Length - 65;
                if (privateOffset < 0 || pointOffset < 0 || pkcs8[pointOffset] != 0x04)
                    throw new CryptographicException("不支持的 EC 私钥格式。需要带公钥点的 P-256 PKCS#8 密钥。");

                var blob = new byte[8 + 32 + 32 + 32];
                Buffer.BlockCopy(BitConverter.GetBytes(0x32534345u), 0, blob, 0, 4); // ECS2
                Buffer.BlockCopy(BitConverter.GetBytes(32u), 0, blob, 4, 4);
                Buffer.BlockCopy(pkcs8, pointOffset + 1, blob, 8, 32);  // X
                Buffer.BlockCopy(pkcs8, pointOffset + 33, blob, 40, 32); // Y
                Buffer.BlockCopy(pkcs8, privateOffset, blob, 72, 32);    // D
                return CngKey.Import(blob, CngKeyBlobFormat.EccPrivateBlob);
            }
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
