param(
  [string]$Out = "C:\Users\Dreitz\AppData\Local\Temp\opencode\logo"
)
# The navigation bar's logo, rendered at 34dp on the television.
#
# The old one was the full  Mundo Donghua lockup: characters, a photo collage,
# wings, a red ring and a curved "MUNDO DONGHUA" subtitle, drawn at 694x622 and
# displayed at 34dp tall. At that size none of it survives. What is left on the
# screen is a small colourful smudge that reads as a compression artefact rather
# than as a brand, next to five crisp wordmarks.
#
# So the mark is reduced to what fits: a plate, and the characters on it. The
# wordmark is not lost so much as moved, since the app's name is the first thing
# in the launcher and the window title; at 34dp a wordmark is not information, it
# is noise.
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

New-Item -ItemType Directory -Force -Path $Out | Out-Null

$S = 512
$ORANGE_TOP    = [System.Drawing.Color]::FromArgb(255, 255, 138, 61)
$ORANGE_BOTTOM = [System.Drawing.Color]::FromArgb(255, 222, 97, 22)
$WHITE         = [System.Drawing.Color]::FromArgb(255, 255, 255, 255)

function New-Badge([string]$glyphs, [double]$size, [string]$fontName) {
  $bmp = New-Object System.Drawing.Bitmap($S, $S)
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.SmoothingMode     = 'AntiAlias'
  $g.InterpolationMode = 'HighQualityBicubic'
  $g.PixelOffsetMode   = 'HighQuality'
  $g.TextRenderingHint = 'AntiAliasGridFit'
  $g.Clear([System.Drawing.Color]::Transparent)

  # The plate, as a squircle, matching the launcher icon's tile.
  $r = $S * 0.235
  $plate = New-Object System.Drawing.Drawing2D.GraphicsPath
  $plate.AddArc(0, 0, $r * 2, $r * 2, 180, 90)
  $plate.AddArc($S - $r * 2, 0, $r * 2, $r * 2, 270, 90)
  $plate.AddArc($S - $r * 2, $S - $r * 2, $r * 2, $r * 2, 0, 90)
  $plate.AddArc(0, $S - $r * 2, $r * 2, $r * 2, 90, 90)
  $plate.CloseFigure()

  $brush = [System.Drawing.Drawing2D.LinearGradientBrush]::new(
    [System.Drawing.PointF]::new(0, 0),
    [System.Drawing.PointF]::new($S, $S),
    $ORANGE_TOP, $ORANGE_BOTTOM
  )
  $g.FillPath($brush, $plate)

  $font = [System.Drawing.Font]::new($fontName, [single]($S * $size), [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
  $fmt = [System.Drawing.StringFormat]::new()
  $fmt.Alignment = [System.Drawing.StringAlignment]::Center
  $fmt.LineAlignment = [System.Drawing.StringAlignment]::Center
  $sb = [System.Drawing.SolidBrush]::new($WHITE)
  $layout = [System.Drawing.RectangleF]::new(0, $S * 0.055, $S, $S * 0.89)
  $g.DrawString($glyphs, $font, $sb, $layout, $fmt)

  $g.Dispose()
  return @{ image = $bmp; font = $font }
}

# How much of the plate the glyphs take, and which face draws them. YaHei is the
# face that is present everywhere this runs, and it is the one whose CJK forms are
# the least fussy at small sizes: SimSun's serifs turn to mud at 34dp.
$variants = @(
  @{ name = "A  Mundo (0.40)"; glyphs = [char]0x4E16 + [string][char]0x754C; size = 0.40; font = "Microsoft YaHei" },
  @{ name = "B  Mundo (0.46)"; glyphs = [char]0x4E16 + [string][char]0x754C; size = 0.46; font = "Microsoft YaHei" },
  @{ name = "C  斗 (0.62)";     glyphs = [string][char]0x6597;                size = 0.62; font = "Microsoft YaHei" },
  @{ name = "D  Mundo (0.46 JhengHei)"; glyphs = [char]0x4E16 + [string][char]0x754C; size = 0.46; font = "Microsoft JhengHei" }
)
$sheet = New-Object System.Drawing.Bitmap(1240, 640)
$gs = [System.Drawing.Graphics]::FromImage($sheet)
$gs.Clear([System.Drawing.Color]::FromArgb(15, 19, 27))
$gs.InterpolationMode = 'HighQualityBicubic'
$gs.TextRenderingHint = 'ClearTypeGridFit'
$font = New-Object System.Drawing.Font("Segoe UI", 13)

$x = 24
foreach ($v in $variants) {
  $r = New-Badge $v.glyphs $v.size $v.font
  $gs.DrawImage($r.image, $x, 30, 160, 160)
  $gs.DrawString($v.name, $font, [System.Drawing.Brushes]::White, $x, 200)
  # And the sizes it is actually seen at, on the real bar.
  $xx = $x
  foreach ($dp in @(34, 24, 16)) {
    $px = [int]($dp * 1.6)
    $gs.DrawImage($r.image, $xx, 240, $px, $px)
    $gs.DrawString("$dp", $font, [System.Drawing.Brushes]::Gray, $xx, 240 + $px + 4)
    $xx += $px + 14
  }
  $r.image.Dispose(); $r.font.Dispose()
  $x += 300
}
# The old one at the same sizes, for comparison.
$gs.DrawString("old, at 34dp", $font, [System.Drawing.Brushes]::White, 24, 420)
$old = [System.Drawing.Image]::FromFile((Resolve-Path "app\src\main\res\drawable\logo_md.png"))
$xx = 24
foreach ($h in @(54, 38, 26)) {
  $w = [int]($old.Width * $h / $old.Height)
  $gs.DrawImage($old, $xx, 450, $w, $h)
  $xx += $w + 20
}
$old.Dispose()
$gs.Dispose()
$sheet.Save("$Out\variants.png", [System.Drawing.Imaging.ImageFormat]::Png)
$sheet.Dispose()
Write-Output "$Out\variants.png"

# ---------------------------------------------------------------- the asset
# The winner of the comparison above: the two characters side by side, which is
# the only arrangement that stays on one line inside a square and stays legible
# at 16dp. The two that wrapped to two lines read as a vertical wordmark in a
# square plate, and the single character reads as a rune rather than as a name.
$chosen = @{ glyphs = [char]0x4E16 + [string][char]0x754C; size = 0.40; font = "Microsoft YaHei" }
$final = New-Badge $chosen.glyphs $chosen.size $chosen.font

# 4x the 40dp the bar gives it, so it is crisp on a panel that reports a low
# density, which is most of them. The old file was 694px wide for a 38dp slot,
# which is 18x oversampled and cost 300KB in the APK to save nothing.
$dir = "app\src\main\res\drawable-xxxhdpi"
New-Item -ItemType Directory -Force -Path $dir | Out-Null
foreach ($px in @(160)) {
  $dst = New-Object System.Drawing.Bitmap($px, $px)
  $g2 = [System.Drawing.Graphics]::FromImage($dst)
  $g2.InterpolationMode = 'HighQualityBicubic'
  $g2.PixelOffsetMode   = 'HighQuality'
  $g2.Clear([System.Drawing.Color]::Transparent)
  $g2.DrawImage($final.image, 0, 0, $px, $px)
  $g2.Dispose()
  $path = Join-Path $dir "logo_md.png"
  if (Test-Path $path) { Remove-Item $path -Force }
  $dst.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
  $dst.Dispose()
  Write-Output ("logo_md.png  {0}x{0}" -f $px)
}
$final.image.Dispose(); $final.font.Dispose()
