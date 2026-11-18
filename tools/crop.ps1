param(
  [Parameter(Position=0)][int]$X = 0,
  [Parameter(Position=1)][int]$Y = 0,
  [Parameter(Position=2)][int]$W = 400,
  [Parameter(Position=3)][int]$H = 300,
  [Parameter(Position=4)][int]$Zoom = 3,
  [string]$Src = "C:\Users\Dreitz\AppData\Local\Temp\opencode\shot.raw.png",
  [string]$Dst = "C:\Users\Dreitz\AppData\Local\Temp\opencode\crop.png"
)
Add-Type -AssemblyName System.Drawing
$img = [System.Drawing.Image]::FromFile($Src)
if ($W -le 0) { $W = $img.Width }
if ($H -le 0) { $H = $img.Height }
$crop = New-Object System.Drawing.Bitmap(($W*$Zoom), ($H*$Zoom))
$g = [System.Drawing.Graphics]::FromImage($crop)
$g.InterpolationMode = 'NearestNeighbor'
$g.PixelOffsetMode = 'Half'
$g.DrawImage($img, (New-Object System.Drawing.Rectangle(0,0,($W*$Zoom),($H*$Zoom))), (New-Object System.Drawing.Rectangle($X,$Y,$W,$H)), 'Pixel')
$g.Dispose()
if (Test-Path $Dst) { Remove-Item $Dst -Force }
$crop.Save($Dst, [System.Drawing.Imaging.ImageFormat]::Png)
$crop.Dispose(); $img.Dispose()
Write-Output "$Dst  ($W x $H @ ${Zoom}x from $X,$Y)"
