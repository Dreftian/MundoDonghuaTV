param(
  [string]$Res = "app\src\main\res",
  [string]$Out = "C:\Users\Dreitz\AppData\Local\Temp\opencode\icons"
)
# Renders the launcher icon at every legacy density from one high resolution
# master, so the square and the round variants are the same drawing rather than
# two files that drifted apart.
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

$SUPER = 1024
$CANVAS = 108.0

# Densities an Android launcher actually asks for, in dp.
$densities = [ordered]@{
  "mdpi"    = 48
  "hdpi"    = 72
  "xhdpi"   = 96
  "xxhdpi"  = 144
  "xxxhdpi" = 192
}

New-Item -ItemType Directory -Force -Path $Out | Out-Null

function New-Icon([bool]$round) {
  $bmp = New-Object System.Drawing.Bitmap($SUPER, $SUPER)
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.SmoothingMode     = 'AntiAlias'
  $g.InterpolationMode = 'HighQualityBicubic'
  $g.PixelOffsetMode   = 'HighQuality'
  $g.Clear([System.Drawing.Color]::Transparent)

  $u = $SUPER / $CANVAS          # canvas units -> pixels
  function P([double]$v) { return [float]($v * $u) }

  # ---- the tile --------------------------------------------------------
  # The mask. A squircle for the square variant, a circle for the round one, so
  # the legacy icon is not a square inside a square with the launcher's own mask
  # on top of it.
  $tile = New-Object System.Drawing.Drawing2D.GraphicsPath
  if ($round) {
    $tile.AddEllipse(0, 0, $SUPER, $SUPER)
  } else {
    # 22.5% radius, which is the squircle proportion launchers use.
    $r = $SUPER * 0.225
    $tile.AddArc(0, 0, $r * 2, $r * 2, 180, 90)
    $tile.AddArc($SUPER - $r * 2, 0, $r * 2, $r * 2, 270, 90)
    $tile.AddArc($SUPER - $r * 2, $SUPER - $r * 2, $r * 2, $r * 2, 0, 90)
    $tile.AddArc(0, $SUPER - $r * 2, $r * 2, $r * 2, 90, 90)
    $tile.CloseFigure()
  }

  # The tile's gradient, three stops on the diagonal, matching
  # ic_launcher_background.xml exactly. ColorBlend only has a
  # ColorBlend(count) constructor, so the stops are written through the
  # Colors and Positions arrays rather than assigned.
  $tileBrush = [System.Drawing.Drawing2D.LinearGradientBrush]::new(
    [System.Drawing.Point]::new(0, 0),
    [System.Drawing.Point]::new($SUPER, $SUPER),
    [System.Drawing.Color]::FromArgb(255, 255, 138, 61),
    [System.Drawing.Color]::FromArgb(255, 178, 58, 12)
  )
  $stops = @(
    @{ pos = 0.00; col = [System.Drawing.Color]::FromArgb(255, 255, 138, 61) },
    @{ pos = 0.45; col = [System.Drawing.Color]::FromArgb(255, 244, 117, 33) },
    @{ pos = 1.00; col = [System.Drawing.Color]::FromArgb(255, 178,  58, 12) }
  )
  $blend = [System.Drawing.Drawing2D.ColorBlend]::new($stops.Count)
  for ($i = 0; $i -lt $stops.Count; $i++) {
    $blend.Colors[$i] = $stops[$i].col
    $blend.Positions[$i] = [single]$stops[$i].pos
  }
  $tileBrush.InterpolationColors = $blend

  $g.SetClip($tile)
  $g.FillRectangle($tileBrush, 0, 0, $SUPER, $SUPER)

  # The soft light in the upper left, matching ic_launcher_background.xml. A
  # triangle rather than a circle: PathGradientBrush fills whatever path it is
  # given, and a triangle is the cheapest way to get a soft falloff without
  # RadialGradientBrush, which this runtime does not carry.
  $hlPts = [System.Drawing.Point[]]@(
    [System.Drawing.Point]::new([int](P 34), [int](P 30)),
    [System.Drawing.Point]::new([int](P -110), [int](P 46)),
    [System.Drawing.Point]::new([int](P 46), [int](P -110))
  )
  $hl = [System.Drawing.Drawing2D.PathGradientBrush]::new($hlPts)
  $hl.CenterColor = [System.Drawing.Color]::FromArgb(80, 255, 255, 255)
  $hl.SurroundColors = @([System.Drawing.Color]::FromArgb(0, 255, 255, 255))
  $g.FillPath($hl, (New-Object System.Drawing.Drawing2D.GraphicsPath))
  $hlPath = New-Object System.Drawing.Drawing2D.GraphicsPath
  $hlPath.AddEllipse((P 34) - (P 72), (P 30) - (P 72), (P 144), (P 144))
  $g.FillEllipse($hl, (P 34) - (P 72), (P 30) - (P 72), (P 144), (P 144))
  $g.ResetClip()

  # ---- the mark -------------------------------------------------------
  # The ring, as one path with two circles and the even-odd rule, so the hole is
  # a hole and not a second colour that has to be guessed.
  $scale = 1.25
  $cx = 54.0; $cy = 54.0
  $rOut = 28.0 * $scale
  $rIn  = 22.0 * $scale

  $mark = New-Object System.Drawing.Drawing2D.GraphicsPath
  $mark.FillMode = [System.Drawing.Drawing2D.FillMode]::Alternate
  $mark.AddEllipse((P ($cx - $rOut)), (P ($cy - $rOut)), (P ($rOut * 2)), (P ($rOut * 2)))
  $mark.AddEllipse((P ($cx - $rIn)),  (P ($cy - $rIn)),  (P ($rIn * 2)),  (P ($rIn * 2)))

  $tri = @(
    (New-Object System.Drawing.PointF((P ($cx + (43.5 - $cx) * $scale)), (P ($cy + (40.5 - $cy) * $scale)))),
    (New-Object System.Drawing.PointF((P ($cx + (69.0 - $cx) * $scale)), (P ($cy + (54.0 - $cy) * $scale)))),
    (New-Object System.Drawing.PointF((P ($cx + (43.5 - $cx) * $scale)), (P ($cy + (67.5 - $cy) * $scale))))
  )
  $mark.AddPolygon($tri)

  $white = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::White)
  $g.FillPath($white, $mark)
  $white.Dispose()

  $g.Dispose()
  return $bmp
}

function Save-Downsampled($bmp, $path, $size) {
  $dst = New-Object System.Drawing.Bitmap($size, $size)
  $g = [System.Drawing.Graphics]::FromImage($dst)
  $g.InterpolationMode = 'HighQualityBicubic'
  $g.PixelOffsetMode   = 'HighQuality'
  $g.SmoothingMode     = 'AntiAlias'
  $g.Clear([System.Drawing.Color]::Transparent)
  $g.DrawImage($bmp, 0, 0, $size, $size)
  $g.Dispose()
  if (Test-Path $path) { Remove-Item $path -Force }
  $dst.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
  $dst.Dispose()
}

$square = New-Icon $false
$circle = New-Icon $true

foreach ($d in $densities.GetEnumerator()) {
  $dir = Join-Path $Res "mipmap-$($d.Key)"
  New-Item -ItemType Directory -Force -Path $dir | Out-Null
  Save-Downsampled $square (Join-Path $dir "ic_launcher.png") $d.Value
  Save-Downsampled $circle (Join-Path $dir "ic_launcher_round.png") $d.Value
  Write-Output ("mipmap-{0,-8} {1,3}dp  -> {2}px" -f $d.Key, $d.Value, $d.Value)
}

# A contact sheet so the result can be looked at before it ships.
$sheet = New-Object System.Drawing.Bitmap(1120, 560)
$g = [System.Drawing.Graphics]::FromImage($sheet)
$g.Clear([System.Drawing.Color]::FromArgb(18, 22, 30))
$g.InterpolationMode = 'HighQualityBicubic'
$font = New-Object System.Drawing.Font("Segoe UI", 13)
$brush = [System.Drawing.Brushes]::White
$labels = @(
  @{ img = $square; name = "legacy square" },
  @{ img = $circle; name = "legacy round" },
  @{ img = $square; name = "48dp (mdpi)" },
  @{ img = $square; name = "adaptive, circular mask" }
)
$x = 20
foreach ($l in $labels) {
  $size = 180
  $g.DrawImage($l.img, $x, 40, $size, $size)
  $g.DrawString($l.name, $font, $brush, $x, 230)
  $x += 220
}
# At true size, on a dark launcher: 48, 36 and 24 dp.
$xs = 20
foreach ($dp in @(48, 36, 24)) {
  $px = [int]($dp * 2.0)
  $g.DrawImage($square, $xs, 300, $px, $px)
  $g.DrawString("$dp" + "dp", $font, $brush, $xs, 300 + $px + 6)
  $xs += $px + 30
}
$g.DrawString("the old icon, at 48dp", $font, $brush, $xs, 300)
$old = [System.Drawing.Image]::FromFile((Resolve-Path (Join-Path $Res "drawable\ic_launcher.png")))
$g.DrawImage($old, $xs, 340, 96, 96)
$g.DrawImage($old, $xs + 140, 340, 72, 72)
$g.DrawImage($old, $xs + 240, 340, 48, 48)
$g.Dispose()
$sheet.Save("$Out\sheet.png", [System.Drawing.Imaging.ImageFormat]::Png)
$sheet.Dispose()
$square.Dispose(); $circle.Dispose(); $old.Dispose()
Write-Output "sheet: $Out\sheet.png"
