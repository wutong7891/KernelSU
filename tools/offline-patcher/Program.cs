using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Reflection;
using System.Text;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace YipaSUOfflinePatcher
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
        private readonly TextBox inputBox = new TextBox();
        private readonly Label outputHint = new Label();
        private readonly ComboBox kmiBox = new ComboBox();
        private readonly CheckBox allowShellBox = new CheckBox();
        private readonly CheckBox enableAdbBox = new CheckBox();
        private readonly TextBox logBox = new TextBox();
        private readonly Button patchButton = new Button();
        private readonly Button restoreButton = new Button();
        private string enginePath;

        public MainForm()
        {
            Text = "YipaSU 脱机镜像工坊";
            ClientSize = new Size(760, 590);
            MinimumSize = new Size(700, 560);
            StartPosition = FormStartPosition.CenterScreen;
            Font = new Font("Microsoft YaHei UI", 9F);
            BackColor = Color.FromArgb(10, 15, 26);
            ForeColor = Color.FromArgb(220, 240, 248);
            AllowDrop = true;

            var title = new Label
            {
                Text = "YipaSU 脱机镜像工坊",
                Font = new Font("Microsoft YaHei UI", 19F, FontStyle.Bold),
                ForeColor = Color.FromArgb(55, 230, 255),
                AutoSize = true,
                Location = new Point(24, 18)
            };
            var subtitle = new Label
            {
                Text = "boot / init_boot 本地修补 · 全程无需联网 · 支持拖放镜像",
                ForeColor = Color.FromArgb(134, 161, 181),
                AutoSize = true,
                Location = new Point(28, 61)
            };

            Controls.Add(title);
            Controls.Add(subtitle);
            AddRow("原始镜像", inputBox, 100, BrowseInput);

            outputHint.Text = "输出：选择镜像后自动保存在原目录（.img）";
            outputHint.ForeColor = Color.FromArgb(125, 255, 178);
            outputHint.AutoEllipsis = true;
            outputHint.SetBounds(125, 143, 607, 28);
            outputHint.Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right;
            Controls.Add(new Label { Text = "自动输出", AutoSize = true, Location = new Point(28, 147) });
            Controls.Add(outputHint);

            Controls.Add(new Label { Text = "KMI 版本", AutoSize = true, Location = new Point(28, 190) });
            kmiBox.DropDownStyle = ComboBoxStyle.DropDownList;
            kmiBox.Items.AddRange(new object[]
            {
                "自动识别（推荐）", "android12-5.10", "android13-5.10", "android13-5.15",
                "android14-5.15", "android14-6.1", "android15-6.6", "android16-6.12"
            });
            kmiBox.SelectedIndex = 0;
            kmiBox.SetBounds(125, 185, 260, 30);
            Controls.Add(kmiBox);

            allowShellBox.Text = "允许 shell 获取 Root";
            allowShellBox.SetBounds(420, 187, 160, 28);
            enableAdbBox.Text = "启用调试 ADB";
            enableAdbBox.SetBounds(590, 187, 135, 28);
            Controls.Add(allowShellBox);
            Controls.Add(enableAdbBox);

            patchButton.Text = "开始脱机修补";
            patchButton.BackColor = Color.FromArgb(62, 102, 210);
            patchButton.ForeColor = Color.White;
            patchButton.FlatStyle = FlatStyle.Flat;
            patchButton.SetBounds(125, 230, 190, 42);
            patchButton.Click += async (_, __) => await RunPatch(false);
            Controls.Add(patchButton);

            restoreButton.Text = "移除 KernelSU / 恢复";
            restoreButton.SetBounds(330, 230, 190, 42);
            restoreButton.Click += async (_, __) => await RunPatch(true);
            Controls.Add(restoreButton);

            var clearButton = new Button { Text = "清空日志" };
            clearButton.SetBounds(535, 230, 110, 42);
            clearButton.Click += (_, __) => logBox.Clear();
            Controls.Add(clearButton);

            logBox.Multiline = true;
            logBox.ReadOnly = true;
            logBox.ScrollBars = ScrollBars.Both;
            logBox.WordWrap = false;
            logBox.BackColor = Color.FromArgb(25, 29, 38);
            logBox.ForeColor = Color.FromArgb(220, 230, 240);
            logBox.Font = new Font("Consolas", 9F);
            logBox.Anchor = AnchorStyles.Top | AnchorStyles.Bottom | AnchorStyles.Left | AnchorStyles.Right;
            logBox.SetBounds(28, 292, 704, 270);
            Controls.Add(logBox);

            DragEnter += (_, e) => e.Effect = e.Data.GetDataPresent(DataFormats.FileDrop)
                ? DragDropEffects.Copy : DragDropEffects.None;
            DragDrop += (_, e) =>
            {
                var files = e.Data.GetData(DataFormats.FileDrop) as string[];
                if (files != null && files.Length > 0) SetInput(files[0]);
            };

            FormClosed += (_, __) => TryDeleteEngine();
            AppendLog("就绪：请选择或拖入原始 boot/init_boot 镜像。\r\n");
        }

        private void AddRow(string label, TextBox box, int y, EventHandler browse)
        {
            Controls.Add(new Label { Text = label, AutoSize = true, Location = new Point(28, y + 6) });
            box.SetBounds(125, y, 510, 30);
            box.Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right;
            Controls.Add(box);
            var button = new Button { Text = "浏览…" };
            button.SetBounds(647, y - 1, 85, 32);
            button.Anchor = AnchorStyles.Top | AnchorStyles.Right;
            button.Click += browse;
            Controls.Add(button);
        }

        private void BrowseInput(object sender, EventArgs e)
        {
            using (var dialog = new OpenFileDialog
            {
                Filter = "Android 镜像 (*.img)|*.img|所有文件 (*.*)|*.*",
                Title = "选择 boot 或 init_boot 镜像"
            })
            {
                if (dialog.ShowDialog(this) == DialogResult.OK) SetInput(dialog.FileName);
            }
        }

        private void SetInput(string path)
        {
            inputBox.Text = path;
            outputHint.Text = "输出：" + GetOutputPath(false);
        }

        private string GetOutputPath(bool restore)
        {
            var source = Path.GetFullPath(inputBox.Text);
            var dir = Path.GetDirectoryName(source) ?? Environment.CurrentDirectory;
            var name = Path.GetFileNameWithoutExtension(source);
            return Path.Combine(dir, name + (restore ? "_YipaSU_restored.img" : "_YipaSU_patched.img"));
        }

        private async Task RunPatch(bool restore)
        {
            if (!File.Exists(inputBox.Text))
            {
                MessageBox.Show(this, "请选择有效的原始镜像。", "YipaSU", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }
            var outputPath = GetOutputPath(restore);

            patchButton.Enabled = restoreButton.Enabled = false;
            try
            {
                EnsureEngine();
                Directory.CreateDirectory(Path.GetDirectoryName(outputPath));
                var args = new StringBuilder();
                args.Append(restore ? "boot-restore" : "boot-patch");
                args.Append(" --boot ").Append(Quote(inputBox.Text));
                args.Append(" --out ").Append(Quote(Path.GetDirectoryName(outputPath)));
                args.Append(" --out-name ").Append(Quote(Path.GetFileName(outputPath)));
                if (!restore && kmiBox.SelectedIndex > 0)
                    args.Append(" --kmi ").Append(kmiBox.SelectedItem.ToString());
                if (!restore && allowShellBox.Checked) args.Append(" --allow-shell");
                if (!restore && enableAdbBox.Checked) args.Append(" --enable-adbd");

                AppendLog("\r\n> 开始" + (restore ? "恢复" : "修补") + "\r\n");
                var start = new ProcessStartInfo(enginePath, args.ToString())
                {
                    UseShellExecute = false,
                    CreateNoWindow = true,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                    StandardOutputEncoding = Encoding.UTF8,
                    StandardErrorEncoding = Encoding.UTF8
                };
                using (var process = new Process { StartInfo = start, EnableRaisingEvents = true })
                {
                    process.OutputDataReceived += (_, e) => { if (e.Data != null) AppendLog(e.Data + "\r\n"); };
                    process.ErrorDataReceived += (_, e) => { if (e.Data != null) AppendLog(e.Data + "\r\n"); };
                    process.Start();
                    process.BeginOutputReadLine();
                    process.BeginErrorReadLine();
                    await Task.Run(() => process.WaitForExit());
                    if (process.ExitCode != 0)
                        throw new InvalidOperationException("处理失败，退出代码：" + process.ExitCode);
                }
                outputHint.Text = "输出：" + outputPath;
                AppendLog("完成：" + outputPath + "\r\n");
                MessageBox.Show(this, "处理完成。\n\n" + outputPath, "YipaSU", MessageBoxButtons.OK, MessageBoxIcon.Information);
            }
            catch (Exception ex)
            {
                AppendLog("错误：" + ex.Message + "\r\n");
                MessageBox.Show(this, ex.Message, "处理失败", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
            finally
            {
                patchButton.Enabled = restoreButton.Enabled = true;
            }
        }

        private void EnsureEngine()
        {
            var dir = Path.Combine(Path.GetTempPath(), "YipaSUOfflinePatcher");
            Directory.CreateDirectory(dir);
            enginePath = Path.Combine(dir, "ksud.exe");
            using (var input = Assembly.GetExecutingAssembly().GetManifestResourceStream("YipaSUOfflinePatcher.ksud.exe"))
            {
                if (input == null) throw new InvalidOperationException("内置脱机修补引擎缺失。");
                using (var output = File.Create(enginePath)) input.CopyTo(output);
            }
        }

        private void TryDeleteEngine()
        {
            try { if (!string.IsNullOrEmpty(enginePath) && File.Exists(enginePath)) File.Delete(enginePath); }
            catch { }
        }

        private void AppendLog(string text)
        {
            if (InvokeRequired) { BeginInvoke(new Action<string>(AppendLog), text); return; }
            logBox.AppendText(text);
        }

        private static string Quote(string value) => "\"" + value.Replace("\"", "\\\"") + "\"";
    }
}
