#!/usr/bin/env python3
"""Tiny SVG -> Android vector drawable converter for the simple icons used here (path / rect / circle / line)."""
import re, sys, os, io

def attrs(tag):
    return dict(re.findall(r'([\w:-]+)="([^"]*)"', tag))

def color(v, opacity=None):
    if v in (None, 'none'):
        return None
    if v == 'currentColor':
        v = '#000000'
    v = v.lstrip('#')
    if len(v) == 3:
        v = ''.join(c * 2 for c in v)
    a = 255 if opacity is None else max(0, min(255, round(float(opacity) * 255)))
    return '#%02X%s' % (a, v.upper())

def rect_path(a):
    x, y = float(a.get('x', 0)), float(a.get('y', 0)); w, h = float(a['width']), float(a['height'])
    r = min(float(a.get('rx', a.get('ry', 0))), w / 2, h / 2)
    if r <= 0:
        return 'M%g,%gh%gv%gh%gz' % (x, y, w, h, -w)
    return ('M%g,%gh%ga%g,%g 0 0 1 %g,%gv%ga%g,%g 0 0 1 %g,%gh%ga%g,%g 0 0 1 %g,%gv%ga%g,%g 0 0 1 %g,%gz'
            % (x + r, y, w - 2 * r, r, r, r, r, h - 2 * r, r, r, -r, r, -(w - 2 * r), r, r, -r, -r, -(h - 2 * r), r, r, r, -r))

def convert(svg, mirror=False):
    root = attrs(re.search(r'<svg[^>]*>', svg).group(0))
    vb = [float(v) for v in root.get('viewBox', '0 0 24 24').split()]
    w = root.get('width', str(vb[2])).replace('px', ''); h = root.get('height', str(vb[3])).replace('px', '')
    out = ['<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    android:width="%sdp"' % w, '    android:height="%sdp"' % h,
           '    android:viewportWidth="%g"' % vb[2], '    android:viewportHeight="%g">' % vb[3]]
    if mirror:
        out.append('  <group android:scaleX="-1" android:pivotX="%g">' % (vb[2] / 2))
    root_fill = root.get('fill')
    for m in re.finditer(r'<(path|rect|circle|line)\b([^>]*)/?>', svg):
        kind, a = m.group(1), attrs(m.group(2))
        if kind == 'path':
            d = a.get('d')
        elif kind == 'rect':
            d = rect_path(a)
        elif kind == 'circle':
            cx, cy, r = float(a['cx']), float(a['cy']), float(a['r'])
            d = 'M%g,%ga%g,%g 0 1 0 %g,0a%g,%g 0 1 0 %g,0z' % (cx - r, cy, r, r, 2 * r, r, r, -2 * r)
        else:
            d = 'M%s,%sL%s,%s' % (a['x1'], a['y1'], a['x2'], a['y2'])
        if not d:
            continue
        fill = a.get('fill', root_fill if 'stroke' not in a else 'none')
        if fill is None and 'stroke' not in a:
            fill = '#000000'
        p = ['    <path']
        fc = color(fill, a.get('fill-opacity', a.get('opacity')))
        if fc:
            p.append('        android:fillColor="%s"' % fc)
            if a.get('fill-rule') == 'evenodd' or a.get('clip-rule') == 'evenodd':
                p.append('        android:fillType="evenOdd"')
        sc = color(a.get('stroke'), a.get('stroke-opacity', a.get('opacity')))
        if sc:
            p.append('        android:strokeColor="%s"' % sc)
            p.append('        android:strokeWidth="%s"' % a.get('stroke-width', '1'))
            if a.get('stroke-linecap'):
                p.append('        android:strokeLineCap="%s"' % a['stroke-linecap'])
            if a.get('stroke-linejoin'):
                p.append('        android:strokeLineJoin="%s"' % a['stroke-linejoin'])
        p.append('        android:pathData="%s"/>' % d)
        out.append('\n'.join(p))
    if mirror:
        out.append('  </group>')
    out.append('</vector>')
    return '\n'.join(out) + '\n'

if __name__ == '__main__':
    src, dst = sys.argv[1], sys.argv[2]
    io.open(dst, 'w', encoding='utf-8').write(convert(io.open(src, encoding='utf-8').read(), mirror='--mirror' in sys.argv))
