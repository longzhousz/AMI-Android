param([Parameter(Mandatory=$true)][string]$Output)
$ErrorActionPreference = 'Stop'
# Windows needs an executable for child_process.spawn; no shell evaluates media URLs.
Add-Type -OutputAssembly $Output -OutputType ConsoleApplication -TypeDefinition @'
using System;
using System.Diagnostics;
using System.Text;
public class AmiMpvTransport {
  static string Quote(string value) {
    var result = new StringBuilder("\""); int slashes = 0;
    foreach (char c in value) {
      if (c == '\\') { slashes++; continue; }
      if (c == '"') { result.Append('\\', slashes * 2 + 1); result.Append(c); }
      else { result.Append('\\', slashes); result.Append(c); }
      slashes = 0;
    }
    result.Append('\\', slashes * 2); result.Append('"'); return result.ToString();
  }
  public static int Main(string[] args) {
    string node = Environment.GetEnvironmentVariable("AMI_BRIDGE_NODE");
    string adapter = Environment.GetEnvironmentVariable("AMI_BRIDGE_ADAPTER");
    if (String.IsNullOrEmpty(node) || String.IsNullOrEmpty(adapter)) return 2;
    var values = new StringBuilder(Quote(adapter));
    foreach (string arg in args) values.Append(" ").Append(Quote(arg));
    var info = new ProcessStartInfo(node, values.ToString()) { UseShellExecute = false, CreateNoWindow = true };
    using (var child = Process.Start(info)) { child.WaitForExit(); return child.ExitCode; }
  }
}
'@
