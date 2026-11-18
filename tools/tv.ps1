param(
  [Parameter(Position=0)][string]$Cmd = "shot",
  [Parameter(Position=1)][string]$Name = "shot",
  [Parameter(Position=2)][string]$Arg = ""
)
$ErrorActionPreference = "Continue"
$adb  = "C:\Users\Dreitz\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$dev  = "192.168.18.22:5555"
$out  = "C:\Users\Dreitz\AppData\Local\Temp\opencode"
New-Item -ItemType Directory -Force -Path $out 2>$null | Out-Null

function Shot($file) {
  $full = Join-Path $out $file
  & $adb -s $dev shell screencap -p /sdcard/_s.png 2>$null | Out-Null
  & $adb -s $dev pull /sdcard/_s.png $full 2>$null | Out-Null
  Write-Output $full
}
function ShotSmall($file, $w = 1400) {
  $full = Join-Path $out $file
  & $adb -s $dev shell screencap -p /sdcard/_s.png 2>$null | Out-Null
  $raw = [System.IO.Path]::ChangeExtension($full, ".raw.png")
  & $adb -s $dev pull /sdcard/_s.png $raw 2>$null | Out-Null
  Add-Type -AssemblyName System.Drawing
  $src = [System.Drawing.Image]::FromFile($raw)
  $h = [int]($src.Height * $w / $src.Width)
  $bmp = New-Object System.Drawing.Bitmap($w, $h)
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.InterpolationMode = 'HighQualityBicubic'
  $g.DrawImage($src, 0, 0, $w, $h)
  $g.Dispose()
  $bmp.Save($full, [System.Drawing.Imaging.ImageFormat]::Png)
  $bmp.Dispose(); $src.Dispose()
  Write-Output "$full  |raw: $raw"
}
function Key($k, $times = 1, $delay = 400) {
  for ($i = 0; $i -lt $times; $i++) { & $adb -s $dev shell input keyevent $k 2>$null | Out-Null; Start-Sleep -Milliseconds $delay }
}

switch ($Cmd) {
  "shot"     { ShotSmall "$Name.png" }
  "shotfull" { Shot "$Name.png" }
  "key"      { Key $Name ([int]$Arg) }
  "install"  { & $adb -s $dev install -r $Name }
  "uninstall"{ & $adb -s $dev uninstall $Name }
  "start"    { & $adb -s $dev shell am start -n com.donghua.tv/.MainActivity }
  "log"      { & $adb -s $dev logcat -d -s "*:E" | Select-Object -Last 60 }
  "clearlog" { & $adb -s $dev logcat -c }
  default    { & $adb -s $dev shell $Cmd $Arg }
}
