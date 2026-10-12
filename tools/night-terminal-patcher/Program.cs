using System.Diagnostics;
using System.Security.Cryptography;
using System.Text;

namespace NightPatcher;

internal static class Program
{
    private static readonly string[] SupportedKmis =
    [
        "android12-5.10", "android13-5.10", "android13-5.15", "android14-5.15",
        "android14-6.1", "android15-6.6", "android16-6.12", "android17-6.18"
    ];

    private static readonly string Root = AppContext.BaseDirectory;

    private static int Main(string[] args)
    {
        try
        {
            return Run(args);
        }
        catch (Exception exception)
        {
            Console.Error.WriteLine($"错误：{exception.Message}");
            return 1;
        }
    }

    private static int Run(string[] args)
    {
        if (args.Length == 1 && args[0].Equals("list", StringComparison.OrdinalIgnoreCase))
        {
            foreach (var kmi in SupportedKmis)
                Console.WriteLine(kmi);
            return 0;
        }

        if (args.Length < 2 || args.Length > 4)
        {
            PrintUsage();
            return 2;
        }

        var command = args[0].ToLowerInvariant();
        if (command is not ("patch" or "restore"))
        {
            PrintUsage();
            return 2;
        }

        if (command == "patch" && args.Length is not (3 or 4))
            throw new ArgumentException("patch 需要镜像路径和 KMI 参数。");
        if (command == "restore" && args.Length is not (2 or 3))
            throw new ArgumentException("restore 只需要镜像路径，可选指定输出路径。");

        var image = Path.GetFullPath(args[1]);
        ValidateImage(image);

        var kmi = command == "patch" ? args[2] : null;
        if (kmi is not null && !SupportedKmis.Contains(kmi, StringComparer.Ordinal))
            throw new ArgumentException($"不支持的 KMI：{kmi}。请运行 list 查看支持列表。");

        var outputArgument = command == "patch"
            ? (args.Length == 4 ? args[3] : null)
            : (args.Length == 3 ? args[2] : null);
        var output = ResolveOutput(image, outputArgument, command);
        if (File.Exists(output))
            throw new IOException($"输出文件已存在，拒绝覆盖：{output}");
        Directory.CreateDirectory(Path.GetDirectoryName(output)!);

        var engine = Path.Combine(Root, "bin", "windows", "ksud.exe");
        VerifyPackageAsset(engine, "bin/windows/ksud.exe");

        string? module = null;
        if (kmi is not null)
        {
            module = Path.Combine(Root, "kmi", $"{kmi}_kernelsu.ko");
            VerifyPackageAsset(module, $"kmi/{kmi}_kernelsu.ko");
        }

        Console.WriteLine($"输入：{image}");
        Console.WriteLine($"输入 SHA-256：{Sha256(image)}");
        if (kmi is not null)
            Console.WriteLine($"KMI：{kmi}");

        var process = new ProcessStartInfo
        {
            FileName = engine,
            UseShellExecute = false
        };
        process.ArgumentList.Add(command == "patch" ? "boot-patch" : "boot-restore");
        process.ArgumentList.Add("--boot");
        process.ArgumentList.Add(image);
        if (module is not null)
        {
            process.ArgumentList.Add("--module");
            process.ArgumentList.Add(module);
            process.ArgumentList.Add("--kmi");
            process.ArgumentList.Add(kmi!);
        }
        process.ArgumentList.Add("--out");
        process.ArgumentList.Add(Path.GetDirectoryName(output)!);
        process.ArgumentList.Add("--out-name");
        process.ArgumentList.Add(Path.GetFileName(output));

        using var child = Process.Start(process) ?? throw new InvalidOperationException("无法启动修补引擎。");
        child.WaitForExit();
        if (child.ExitCode != 0)
            throw new InvalidOperationException($"修补引擎退出代码：{child.ExitCode}");
        if (!File.Exists(output) || new FileInfo(output).Length == 0)
            throw new IOException("修补引擎未生成输出镜像。");

        var report = output + ".SHA256.txt";
        var lines = new List<string>
        {
            "Night Terminal Patcher v3",
            $"Operation: {command}",
            $"Input: {image}",
            $"Input SHA-256: {Sha256(image)}"
        };
        if (kmi is not null)
            lines.Add($"KMI: {kmi}");
        lines.Add($"Output: {output}");
        lines.Add($"Output SHA-256: {Sha256(output)}");
        File.WriteAllLines(report, lines, new UTF8Encoding(false));

        Console.WriteLine($"完成：{output}");
        Console.WriteLine($"校验报告：{report}");
        return 0;
    }

    private static void ValidateImage(string image)
    {
        if (!File.Exists(image))
            throw new FileNotFoundException("镜像不存在。", image);
        Span<byte> header = stackalloc byte[8];
        using var stream = File.OpenRead(image);
        if (stream.Read(header) != header.Length || Encoding.ASCII.GetString(header) != "ANDROID!")
            throw new InvalidDataException("不是未压缩的 Android boot/init_boot 镜像（缺少 ANDROID! 文件头）。");
    }

    private static string ResolveOutput(string image, string? requested, string command)
    {
        if (!string.IsNullOrWhiteSpace(requested))
            return Path.GetFullPath(requested);
        var suffix = command == "patch" ? "_Night_patched.img" : "_Night_restored.img";
        return Path.Combine(Path.GetDirectoryName(image)!, Path.GetFileNameWithoutExtension(image) + suffix);
    }

    private static void VerifyPackageAsset(string absolutePath, string relativePath)
    {
        if (!File.Exists(absolutePath))
            throw new FileNotFoundException("工具包文件缺失。", absolutePath);
        var manifest = Path.Combine(Root, "SHA256.txt");
        if (!File.Exists(manifest))
            throw new FileNotFoundException("工具包完整性清单缺失。", manifest);

        var normalized = relativePath.Replace('\\', '/');
        string? expected = null;
        foreach (var line in File.ReadLines(manifest))
        {
            var parts = line.Split((char[]?)null, 2, StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length == 2 && parts[1].TrimStart('*').Replace('\\', '/') == normalized)
            {
                expected = parts[0].ToLowerInvariant();
                break;
            }
        }
        if (expected is null)
            throw new InvalidDataException($"SHA-256 清单没有此文件：{relativePath}");
        if (!CryptographicOperations.FixedTimeEquals(
                Convert.FromHexString(expected), Convert.FromHexString(Sha256(absolutePath))))
            throw new InvalidDataException($"SHA-256 校验失败：{relativePath}");
    }

    private static string Sha256(string file)
    {
        using var stream = File.OpenRead(file);
        return Convert.ToHexString(SHA256.HashData(stream)).ToLowerInvariant();
    }

    private static void PrintUsage()
    {
        Console.WriteLine("Night Terminal Patcher v3");
        Console.WriteLine();
        Console.WriteLine("用法：");
        Console.WriteLine("  NightPatcher.exe list");
        Console.WriteLine("  NightPatcher.exe patch <boot.img|init_boot.img> <KMI> [output.img]");
        Console.WriteLine("  NightPatcher.exe restore <patched.img> [output.img]");
        Console.WriteLine();
        Console.WriteLine("示例：NightPatcher.exe patch init_boot.img android14-6.1");
    }
}
