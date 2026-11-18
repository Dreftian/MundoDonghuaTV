param([string]$Path)
Add-Type -AssemblyName System.Drawing
$b = New-Object System.Drawing.Bitmap($Path)
Write-Output "=== $(Split-Path $Path -Leaf) ($($b.Width)x$($b.Height)) ==="

function Bands([int[]]$ys) {
  $out = @(); $prev = -10; $start = 0
  foreach ($y in $ys) {
    if ($y -ne $prev + 1) { if ($prev -ge 0) { $out += "$start-$prev" }; $start = $y }
    $prev = $y
  }
  if ($prev -ge 0) { $out += "$start-$prev" }
  return ($out -join ', ')
}

$ys = @()
for ($y = 0; $y -lt $b.Height; $y++) {
  $c = 0
  for ($x = 0; $x -lt $b.Width; $x += 2) {
    $p = $b.GetPixel($x, $y)
    if ($p.R -gt 140 -and $p.G -gt 140 -and $p.B -gt 140) { $c++ }
  }
  if ($c -gt 3) { $ys += $y }
}
Write-Output "bright bands (y): $(Bands $ys)"

foreach ($y in @(90, 150, 700, 790, 860, 1000)) {
  if ($y -ge $b.Height) { continue }
  $xs = @()
  for ($x = 0; $x -lt $b.Width; $x++) {
    $p = $b.GetPixel($x, $y)
    if ($p.R -gt 140 -and $p.G -gt 140 -and $p.B -gt 140) { $xs += $x }
  }
  if ($xs.Count) { Write-Output ("y={0,4}  x={1}..{2}" -f $y, $xs[0], $xs[-1]) }
  else { Write-Output ("y={0,4}  (none)" -f $y) }
}
$b.Dispose()
