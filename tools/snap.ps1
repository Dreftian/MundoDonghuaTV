param(
  [Parameter(Position=0)][string]$Name = "shot",
  [string]$Expect = ""
)
$ErrorActionPreference = "Continue"
$adb  = "C:\Users\Dreitz\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$dev  = "192.168.18.22:5555"
$out  = "C:\Users\Dreitz\AppData\Local\Temp\opencode"
Add-Type -AssemblyName System.Drawing

function Foreground {
  (& $adb -s $dev shell dumpsys activity activities 2>$null |
    Select-String -Pattern "topResumedActivity=ActivityRecord\{[^}]*\s(com\.[^/ ]+)" |
    Select-Object -First 1).Matches.Groups[1].Value
}

function Snap($file) {
  $raw = Join-Path $out "$file.raw.png"
  # Two grabs: the first can come back with the previous window still composited
  # when an activity has just changed, which reads as a stale screenshot.
  & $adb -s $dev shell screencap -p /sdcard/_c.png 2>$null | Out-Null
  Start-Sleep -Milliseconds 350
  & $adb -s $dev shell screencap -p /sdcard/_c.png 2>$null | Out-Null
  & $adb -s $dev pull /sdcard/_c.png $raw 2>$null | Out-Null
  $src = [System.Drawing.Image]::FromFile($raw)
  $w = 1400; $h = [int]($src.Height * $w / $src.Width)
  $bmp = New-Object System.Drawing.Bitmap($w, $h)
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.InterpolationMode = 'HighQualityBicubic'
  $g.DrawImage($src, 0, 0, $w, $h)
  $g.Dispose()
  $bmp.Save((Join-Path $out "$file.png"), [System.Drawing.Imaging.ImageFormat]::Png)
  $bmp.Dispose(); $src.Dispose()
}

if ($Expect -ne "") {
  $got = ""
  for ($i = 0; $i -lt 6; $i++) {
    $got = Foreground
    if ($got -like $Expect) { break }
    Start-Sleep -Milliseconds 500
  }
  Write-Output "foreground: $got (expected like '$Expect')"
}
Snap $Name
Write-Output "$out\$Name.png"
