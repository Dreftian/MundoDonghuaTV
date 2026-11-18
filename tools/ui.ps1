param(
  [Parameter(Position=0)][string]$Cmd = "open",
  [Parameter(Position=1)][string]$Name = "shot"
)
$ErrorActionPreference = "Continue"
$adb  = "C:\Users\Dreitz\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$dev  = "192.168.18.22:5555"
$out  = "C:\Users\Dreitz\AppData\Local\Temp\opencode"
Add-Type -AssemblyName System.Drawing

function K($k, $ms = 420) { & $adb -s $dev shell input keyevent $k 2>$null | Out-Null; Start-Sleep -Milliseconds $ms }

function Snap($file) {
  $raw = Join-Path $out "$file.raw.png"
  & $adb -s $dev shell screencap -p /sdcard/_c.png 2>$null | Out-Null
  Start-Sleep -Milliseconds 300
  & $adb -s $dev shell screencap -p /sdcard/_c.png 2>$null | Out-Null
  & $adb -s $dev pull /sdcard/_c.png $raw 2>$null | Out-Null
  $src = [System.Drawing.Image]::FromFile($raw)
  $w = 1400; $h = [int]($src.Height * $w / $src.Width)
  $bmp = New-Object System.Drawing.Bitmap($w, $h)
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.InterpolationMode = 'HighQualityBicubic'
  $g.DrawImage($src, 0, 0, $w, $h); $g.Dispose()
  $bmp.Save((Join-Path $out "$file.png"), [System.Drawing.Imaging.ImageFormat]::Png)
  $bmp.Dispose(); $src.Dispose()
  Write-Output "$out\$file.png"
}

function Ui {
  & $adb -s $dev shell uiautomator dump /sdcard/_u.xml 2>$null | Out-Null
  (& $adb -s $dev shell cat /sdcard/_u.xml 2>$null)
}

# Waits until the home grid has actually composed. Pressing DPAD while it is
# still loading moves focus to a screen whose handlers do not exist yet, so the
# next DPAD_CENTER is silently dropped.
function WaitForCards($timeoutSec = 40) {
  for ($i = 0; $i -lt $timeoutSec * 2; $i++) {
    if ((Ui) -match 'Episodios Limitados|Series|Favoritos|Nuevos Episodios') { return $true }
    Start-Sleep -Milliseconds 500
  }
  return $false
}

function WaitForView($id, $timeoutSec = 60) {
  for ($i = 0; $i -lt $timeoutSec * 2; $i++) {
    if ((Ui) -match $id) { return $true }
    Start-Sleep -Milliseconds 500
  }
  return $false
}

switch ($Cmd) {
  "open" {
    & $adb -s $dev shell am force-stop com.donghua.tv 2>$null | Out-Null
    & $adb -s $dev shell am start -n com.donghua.tv/.MainActivity 2>$null | Out-Null
    if (-not (WaitForCards)) { Write-Output "WARN: home grid never appeared" }
    K 20 700              # nav -> grid
    K 66 1500             # open the focused card
    if (-not (WaitForView "fsVideo")) { Write-Output "WARN: player never appeared" }
    Write-Output "player up"
  }
  "chrome" {
    if ((Ui) -notmatch "fsControls") { K 19 500 }   # reveal only if hidden
    Snap $Name
  }
  "strip" {
    K 19 400; K 20 350; K 20 350; K 66 2500         # up, down x2 -> Capítulos, open
    Snap $Name
  }
  "key"  { K $Name 500; Snap "after_$Name" }
  "wait" { WaitForCards }
  default { K $Cmd 500; Snap $Name }
}
