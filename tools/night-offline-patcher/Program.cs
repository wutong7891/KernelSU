using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace NightOfflinePatcher
{
    internal static class Program
    {
        [STAThread]
        private static void Main()
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new MainForm());
        }
    }

    internal sealed class MainForm : Form
    {
        private static readonly string[] Kmis = {
            "android12-5.10", "android13-5.10", "android13-5.15", "android14-5.15",
            "android14-6.1", "android15-6.6", "android16-6.12", "android17-6.18"
        };

        private readonly TextBox inputBox = new TextBox();
        private readonly Label outputHint = new Label();
        private readonly ComboBox kmiBox = new ComboBox();
        private readonly TextBox logBox = new TextBox();
        private readonly Button patchButton = new Button();
        private readonly Button restoreButton = new Button();
        private string workDir;

        public MainForm()
        {
            Text = "Night 脱机镜像工坊";
            ClientSize = new Size(800, 620);
            MinimumSize = new Size(720, 580);
            StartPosition = FormStartPosition.CenterScreen;
            Font = new Font("Microsoft YaHei UI", 9F);
            BackColor = Color.FromArgb(9, 13, 24);
            ForeColor = Color.FromArgb(232, 240, 255);
            AllowDrop = true;

            Controls.Add(new Label {
                Text = "NIGHT  /  OFFLINE IMAGE LAB",
                Font = new Font("Microsoft YaHei UI", 20F, FontStyle.Bold),
                ForeColor = Color.FromArgb(153, 211, 255), AutoSize = true,
                Location = new Point(28, 20)
            });
            Controls.Add(new Label {
                Text = "boot / init_boot 本地修补 · 不联网 · 不连接设备 · 不执行刷写",
                ForeColor = Color.FromArgb(152, 166, 194), AutoSize = true,
                Location = new Point(31, 65)
            });

            AddRow("原始镜像", inputBox, 105, BrowseInput);
            Controls.Add(new Label { Text = "自动输出", AutoSize = true, Location = new Point(31, 154) });
            outputHint.Text = "选择镜像后输出到原目录";
            outputHint.ForeColor = Color.FromArgb(141, 238, 213);
            outputHint.AutoEllipsis = true;
            outputHint.SetBounds(128, 150, 635, 28);
            outputHint.Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right;
            Controls.Add(outputHint);

            Controls.Add(new Label { Text = "专属 KMI", AutoSize = true, Location = new Point(31, 199) });
            kmiBox.DropDownStyle = ComboBoxStyle.DropDownList;
            kmiBox.Items.AddRange(Kmis);
            kmiBox.SelectedIndex = 4;
            kmiBox.SetBounds(128, 193, 270, 31);
            Controls.Add(kmiBox);

            StylePrimary(patchButton, "开始脱机修补", 128, 242, 195);
            patchButton.Click += async (_, __) => await RunPatch(false);
            restoreButton.Text = "移除 KernelSU / 恢复";
            restoreButton.SetBounds(338, 242, 195, 43);
            restoreButton.Click += async (_, __) => await RunPatch(true);
            Controls.Add(restoreButton);
            var clear = new Button { Text = "清空日志" };
            clear.SetBounds(548, 242, 120, 43);
            clear.Click += (_, __) => logBox.Clear();
            Controls.Add(clear);

            logBox.Multiline = true;
            logBox.ReadOnly = true;
            logBox.ScrollBars = ScrollBars.Both;
            logBox.WordWrap = false;
            logBox.BackColor = Color.FromArgb(21, 28, 43);
            logBox.ForeColor = Color.FromArgb(214, 228, 248);
            logBox.Font = new Font("Consolas", 9F);
            logBox.Anchor = AnchorStyles.Top | AnchorStyles.Bottom | AnchorStyles.Left | AnchorStyles.Right;
            logBox.SetBounds(31, 307, 732, 280);
            Controls.Add(logBox);

            DragEnter += (_, e) => e.Effect = e.Data.GetDataPresent(DataFormats.FileDrop)
                ? DragDropEffects.Copy : DragDropEffects.None;
            DragDrop += (_, e) => {
                var files = e.Data.GetData(DataFormats.FileDrop) as string[];
                if (files != null && files.Length > 0) SetInput(files[0]);
            };
            FormClosed += (_, __) => Cleanup();
            AppendLog("就绪。请选择或拖入原始 boot/init_boot 镜像。\r\n");
        }

        private void AddRow(string label, TextBox box, int y, EventHandler browse)
        {
            Controls.Add(new Label { Text = label, AutoSize = true, Location = new Point(31, y + 6) });
            box.SetBounds(128, y, 530, 30);
            box.Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right;
            Controls.Add(box);
            var button = new Button { Text = "浏览…" };
            button.SetBounds(670, y - 1, 93, 32);
            button.Anchor = AnchorStyles.Top | AnchorStyles.Right;
            button.Click += browse;
            Controls.Add(button);
        }

        private void StylePrimary(Button button, string text, int x, int y, int width)
        {
            button.Text = text;
            button.BackColor = Color.FromArgb(65, 105, 225);
            button.ForeColor = Color.White;
            button.FlatStyle = FlatStyle.Flat;
            button.FlatAppearance.BorderColor = Color.FromArgb(126, 193, 255);
            button.SetBounds(x, y, width, 43);
            Controls.Add(button);
        }

        private void BrowseInput(object sender, EventArgs e)
        {
            using (var dialog = new OpenFileDialog {
                Filter = "Android 镜像 (*.img)|*.img|所有文件 (*.*)|*.*",
                Title = "选择 boot 或 init_boot 镜像"
            }) {
                if (dialog.ShowDialog(this) == DialogResult.OK) SetInput(dialog.FileName);
            }
        }

        private void SetInput(string path)
        {
            inputBox.Text = Path.GetFullPath(path);
            outputHint.Text = "输出：" + GetOutputPath(false);
        }

        private string GetOutputPath(bool restore)
        {
            var source = Path.GetFullPath(inputBox.Text);
            var dir = Path.GetDirectoryName(source) ?? Environment.CurrentDirectory;
            var name = Path.GetFileNameWithoutExtension(source);
            return Path.Combine(dir, name + (restore ? "_Night_restored.img" : "_Night_patched.img"));
        }

        private async Task RunPatch(bool restore)
        {
            if (!File.Exists(inputBox.Text)) {
                MessageBox.Show(this, "请选择有效的原始镜像。", "Night", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }
            var outputPath = GetOutputPath(restore);
            if (File.Exists(outputPath)) {
                MessageBox.Show(this, "输出文件已存在，为防止覆盖已停止：\n" + outputPath,
                    "Night", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }

            patchButton.Enabled = restoreButton.Enabled = false;
            try {
                EnsureResources(restore ? null : (string)kmiBox.SelectedItem);
                var engine = Path.Combine(workDir, "ksud.exe");
                var args = new StringBuilder(restore ? "boot-restore" : "boot-patch-v2");
                args.Append(" --boot ").Append(Quote(inputBox.Text));
                if (restore) {
                    args.Append(" --out ").Append(Quote(Path.GetDirectoryName(outputPath)));
                    args.Append(" --out-name ").Append(Quote(Path.GetFileName(outputPath)));
                } else {
                    args.Append(" --module ").Append(Quote(Path.Combine(workDir, "kernelsu.ko")));
                    args.Append(" --output ").Append(Quote(outputPath));
                    args.Append(" --force");
                }

                AppendLog("\r\n> " + (restore ? "恢复" : "修补") + "开始\r\n");
                var start = new ProcessStartInfo(engine, args.ToString()) {
                    UseShellExecute = false, CreateNoWindow = true,
                    RedirectStandardOutput = true, RedirectStandardError = true,
                    StandardOutputEncoding = Encoding.UTF8, StandardErrorEncoding = Encoding.UTF8
                };
                using (var process = new Process { StartInfo = start }) {
                    process.OutputDataReceived += (_, e) => { if (e.Data != null) AppendLog(e.Data + "\r\n"); };
                    process.ErrorDataReceived += (_, e) => { if (e.Data != null) AppendLog(e.Data + "\r\n"); };
                    process.Start();
                    process.BeginOutputReadLine();
                    process.BeginErrorReadLine();
                    await Task.Run(() => process.WaitForExit());
                    if (process.ExitCode != 0 || !File.Exists(outputPath))
                        throw new InvalidOperationException("处理失败，退出代码：" + process.ExitCode);
                }
                var reportPath = outputPath + ".SHA256.txt";
                File.WriteAllText(reportPath, BuildReport(inputBox.Text, outputPath, restore), new UTF8Encoding(false));
                outputHint.Text = "输出：" + outputPath;
                AppendLog("完成：" + outputPath + "\r\n校验报告：" + reportPath + "\r\n");
                MessageBox.Show(this, "处理完成。\n\n" + outputPath, "Night", MessageBoxButtons.OK, MessageBoxIcon.Information);
            } catch (Exception ex) {
                AppendLog("错误：" + ex.Message + "\r\n");
                MessageBox.Show(this, ex.Message, "处理失败", MessageBoxButtons.OK, MessageBoxIcon.Error);
            } finally {
                patchButton.Enabled = restoreButton.Enabled = true;
            }
        }

        private void EnsureResources(string kmi)
        {
            Cleanup();
            workDir = Path.Combine(Path.GetTempPath(), "NightOfflinePatcher-" + Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(workDir);
            Extract("NightOfflinePatcher.ksud.exe", Path.Combine(workDir, "ksud.exe"));
            if (kmi != null)
                Extract("NightOfflinePatcher.KMI." + kmi + "_kernelsu.ko", Path.Combine(workDir, "kernelsu.ko"));
        }

        private static void Extract(string resource, string destination)
        {
            using (var input = Assembly.GetExecutingAssembly().GetManifestResourceStream(resource)) {
                if (input == null) throw new InvalidOperationException("内置脱机资源缺失：" + resource);
                using (var output = File.Create(destination)) input.CopyTo(output);
            }
        }

        private static string BuildReport(string source, string output, bool restore)
        {
            return "Night 脱机镜像修补报告\r\n" +
                "操作: " + (restore ? "恢复" : "修补") + "\r\n" +
                "原始镜像: " + source + "\r\n" +
                "原始镜像 SHA256: " + Sha256(source) + "\r\n" +
                "输出镜像: " + output + "\r\n" +
                "输出镜像 SHA256: " + Sha256(output) + "\r\n" +
                "生成时间: " + DateTimeOffset.Now.ToString("yyyy-MM-dd HH:mm:ss zzz") + "\r\n";
        }

        private static string Sha256(string path)
        {
            using (var sha = SHA256.Create())
            using (var stream = File.OpenRead(path))
                return BitConverter.ToString(sha.ComputeHash(stream)).Replace("-", "").ToLowerInvariant();
        }

        private void Cleanup()
        {
            try { if (!string.IsNullOrEmpty(workDir) && Directory.Exists(workDir)) Directory.Delete(workDir, true); }
            catch { }
            workDir = null;
        }

        private void AppendLog(string text)
        {
            if (InvokeRequired) { BeginInvoke(new Action<string>(AppendLog), text); return; }
            logBox.AppendText(text);
        }

        private static string Quote(string value) => "\"" + value.Replace("\"", "\\\"") + "\"";
    }
}
