# Requires: -Logo (default: the repo's logo_md.png), -Out (default: build/launcher)
#
# The launcher icon used to be a hand-made PNG: the artwork squashed into a
# square, cropped, blurred, and sat inside a white plate with an orange frame.
# This rebuilds it from logo_md.png, which is the artwork at full size and with a
# transparent background, so the plate can be whatever makes it legible.
param(
    [string]$Logo = "$PSScriptRoot\..\app\src\main\res\drawable\logo_md.png",
    [string]$Out  = "$PSScriptRoot\..\build\launcher"
)

Add-Type -AssemblyName System.Drawing

# A variant: a plate, and how much of the logo sits on it.
$variants = @(
    @{ name = 'a-dark-94';  top = '#16202C'; bot = '#0A0E14'; inset = 0.03; radius = 0.22 }
    @{ name = 'b-dark-100'; top = '#16202C'; bot = '#0A0E14'; inset = 0.00; radius = 0.22 }
    @{ name = 'c-white-94'; top = '#FFFFFF'; bot = '#E7EAEF'; inset = 0.03; radius = 0.22 }
    @{ name = 'd-red-94';   top = '#E0392F'; bot = '#B3231B'; inset = 0.03; radius = 0.22 }
)

# Not $logo: PowerShell variable names are case-insensitive, so that would be the
# [string]$Logo parameter, and assigning a Bitmap to it silently stored a string.
$art = [System.Drawing.Image]::FromFile((Resolve-Path $Logo).Path)
New-Item -ItemType Directory -Force -Path $Out | Out-Null

foreach ($v in $variants) {
    # Every launcher-icon density, so the install step below is a copy and not a
    # re-render at a size nothing has checked.
    foreach ($size in @(512, 192, 144, 96, 72, 48)) {
        $bmp = [System.Drawing.Bitmap]::new($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $g.SmoothingMode      = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $g.InterpolationMode  = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $g.PixelOffsetMode    = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
        $g.Clear([System.Drawing.Color]::Transparent)

        # The launcher does not mask: every icon in the row carries its own
        # shape, so the corners are cut here or the icon reads as a black square.
        $rad = [float]($v.radius * $size)
        $d = $rad * 2
        $clip = [System.Drawing.Drawing2D.GraphicsPath]::new()
        $clip.AddArc(0, 0, $d, $d, 180, 90)
        $clip.AddArc($size - $d, 0, $d, $d, 270, 90)
        $clip.AddArc($size - $d, $size - $d, $d, $d, 0, 90)
        $clip.AddArc(0, $size - $d, $d, $d, 90, 90)
        $clip.CloseFigure()
        $g.SetClip($clip)

        $rect = [System.Drawing.RectangleF]::new(0, 0, $size, $size)
        $br = [System.Drawing.Drawing2D.LinearGradientBrush]::new(
            $rect,
            [System.Drawing.ColorTranslator]::FromHtml($v.top),
            [System.Drawing.ColorTranslator]::FromHtml($v.bot),
            [System.Drawing.Drawing2D.LinearGradientMode]::Vertical)
        $g.FillRectangle($br, $rect)
        $br.Dispose()

        # Scaled by width, so both clusters of the logo stay whole: the
        # characters down the left and the wing down the right. Scaled by height
        # they would not, because the logo is wider than it is tall and filling
        # the height would cut the wing off at the edge.
        $w = [float]$size * (1.0 - 2 * [double]$v.inset)
        $h = [float]($w * $art.Height / $art.Width)
        if ($h -gt $size) {
            $h = [float]$size
            $w = [float]($h * $art.Width / $art.Height)
        }
        $g.DrawImage($art,
            [System.Drawing.RectangleF]::new(
                [float](($size - $w) / 2), [float](($size - $h) / 2), $w, $h))

        $clip.Dispose()
        $g.Dispose()
        $bmp.Save((Join-Path $Out ("{0}-{1}.png" -f $v.name, $size)),
                  [System.Drawing.Imaging.ImageFormat]::Png)
        $bmp.Dispose()
    }
}

# Sheet: every variant at the two sizes that matter, on the launcher's own grey,
# so the 48px column shows what it actually looks like in the row.
$big = 192; $small = 48
$sheetW = 40 + $big + 60 + $small + 40
$sheetH = 24 + $variants.Count * ($big + 36)
$sheet = [System.Drawing.Bitmap]::new($sheetW, $sheetH)
$sg = [System.Drawing.Graphics]::FromImage($sheet)
$sg.Clear([System.Drawing.ColorTranslator]::FromHtml('#0A0A0C'))
$sg.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$font = [System.Drawing.Font]::new('Segoe UI', 11)
$y = 16
foreach ($v in $variants) {
    $a = [System.Drawing.Image]::FromFile((Join-Path $Out ("{0}-{1}.png" -f $v.name, $big)))
    $sg.DrawImage($a, 20, $y, $big, $big)
    $a.Dispose()
    $b = [System.Drawing.Image]::FromFile((Join-Path $Out ("{0}-{1}.png" -f $v.name, $small)))
    $sg.DrawImage($b, ($big + 64), $y, $small, $small)
    $b.Dispose()
    $sg.DrawString($v.name, $font, [System.Drawing.Brushes]::White, 20, ($y + $big + 6))
    $y += $big + 36
}
$font.Dispose()
$sg.Dispose()
$sheet.Save((Join-Path $Out 'sheet.png'), [System.Drawing.Imaging.ImageFormat]::Png)
$sheet.Dispose()
# Install. Launcher icons are 48dp, so one 512px PNG in drawable/ is both 500KB
# of dead weight and the wrong size: with no density qualifier the system treats
# it as mdpi and scales it up on every device that is not the smallest.
#
# The rounded plate is drawn into the bitmap rather than left to the launcher
# because this launcher does not mask: YouTube, Netflix and Prime all arrive
# already round, so an unmasked square is the one that looks wrong in the row.
$chosen = $variants[0]   # a-dark-94
$densities = [ordered]@{
    'mdpi'    = 48
    'hdpi'    = 72
    'xhdpi'   = 96
    'xxhdpi'  = 144
    'xxxhdpi' = 192
}
$res = "$PSScriptRoot\..\app\src\main\res"
foreach ($kv in $densities.GetEnumerator()) {
    $dir = Join-Path $res ("mipmap-" + $kv.Key)
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $rendered = Join-Path $Out ("{0}-{1}.png" -f $chosen.name, $kv.Value)
    foreach ($name in @('ic_launcher', 'ic_launcher_round')) {
        # Same artwork for both. There is no round variant of this logo worth
        # having: cropping the wings off to make a circle throws away the part
        # that makes it recognisable at 48px.
        Copy-Item $rendered (Join-Path $dir ($name + '.png')) -Force
    }
}
Write-Output "instalado: $($chosen.name) en $($densities.Count) densidades"

$art.Dispose()

Write-Output "listo: $Out"
