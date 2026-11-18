# Regenerates the tyre-web-app icons (app\src\main\assets\tpmsweb\*.png).
# The PNGs are checked in; run this only if the artwork should change.
# Motif mirrors the TPMS card: dark CardSurface tile with the FL/FR/RL/RR tile grid.
Add-Type -AssemblyName System.Drawing

$out = Join-Path $PSScriptRoot '..\app\src\main\assets\tpmsweb'

function New-Icon([string]$path, [int]$size, [bool]$maskable) {
    $bmp = New-Object System.Drawing.Bitmap $size, $size
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'
    $g.TextRenderingHint = 'AntiAliasGridFit'
    $g.Clear([System.Drawing.Color]::FromArgb(255, 0, 0, 0))

    # Card surface square (maskable art must keep content inside the safe 80% centre).
    $pad = if ($maskable) { [int]($size * 0.16) } else { [int]($size * 0.06) }
    $radius = [single]($size * 0.155)
    $rect = New-Object System.Drawing.Rectangle $pad, $pad, ($size - 2 * $pad), ($size - 2 * $pad)

    $path2 = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $radius * 2
    $path2.AddArc($rect.X, $rect.Y, $d, $d, 180, 90)
    $path2.AddArc($rect.Right - $d, $rect.Y, $d, $d, 270, 90)
    $path2.AddArc($rect.Right - $d, $rect.Bottom - $d, $d, $d, 0, 90)
    $path2.AddArc($rect.X, $rect.Bottom - $d, $d, $d, 90, 90)
    $path2.CloseFigure()

    $surface = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 20, 20, 20))
    $g.FillPath($surface, $path2)

    # Four wheel tiles: 1dp-style hairline borders, 3px corner radius, same layout ratio as the card.
    $inner = [int]($rect.Width * 0.14)
    $gap = [int]($rect.Width * 0.08)
    $cell = [int](($rect.Width - 2 * $inner - $gap) / 2)
    $pen = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(255, 255, 255, 255)), ([single]($size * 0.012))
    $font = New-Object System.Drawing.Font 'Consolas', ([single]($cell * 0.42)), ([System.Drawing.FontStyle]::Bold), ([System.Drawing.GraphicsUnit]::Pixel)
    $textBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 255, 255, 255))
    $fmt = New-Object System.Drawing.StringFormat
    $fmt.Alignment = 'Center'; $fmt.LineAlignment = 'Center'

    $labels = @('2.4', '2.4', '2.3', '2.3')
    $i = 0
    for ($r = 0; $r -lt 2; $r++) {
        for ($c = 0; $c -lt 2; $c++) {
            $x = $rect.X + $inner + $c * ($cell + $gap)
            $y = $rect.Y + $inner + $r * ($cell + $gap)
            $g.DrawRectangle($pen, $x, $y, $cell, $cell)
            $box = New-Object System.Drawing.RectangleF ([single]$x), ([single]$y), ([single]$cell), ([single]$cell)
            $g.DrawString($labels[$i], $font, $textBrush, $box, $fmt)
            $i++
        }
    }

    $g.Dispose()
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Host "wrote $path ($size x $size)"
}

New-Icon (Join-Path $out 'apple-touch-icon.png') 180 $false
New-Icon (Join-Path $out 'icon.png') 512 $true
