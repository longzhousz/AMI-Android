#!/usr/bin/env python3
"""Generate AMI's Android vector icons from design/ami-icon.svg (Python stdlib only)."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
SVG_NS = "{http://www.w3.org/2000/svg}"
ANDROID = "http://schemas.android.com/apk/res/android"
AAPT = "http://schemas.android.com/aapt"
ET.register_namespace("android", ANDROID)
ET.register_namespace("aapt", AAPT)


def attributes(**values):
    return {f"{{{ANDROID}}}{key}": str(value) for key, value in values.items()}


def vector(size="108dp", viewport=1536):
    # AdaptiveIconDrawable adds 25% to each edge: the visible 72dp maps to
    # our original 1024px artwork centered in a 1536px / 108dp layer.
    return ET.Element("vector", attributes(width=size, height=size,
                                         viewportWidth=viewport, viewportHeight=viewport))


def write(name, element):
    path = RES / name
    path.parent.mkdir(parents=True, exist_ok=True)
    ET.indent(element, space="    ")
    path.write_text('<?xml version="1.0" encoding="utf-8"?>\n'
                    '<!-- Generated from design/ami-icon.svg by tools/generate_icon.py. -->\n'
                    + ET.tostring(element, encoding="unicode") + "\n", encoding="utf-8")


def add_gradient(path, gradient):
    item = ET.SubElement(path, f"{{{AAPT}}}attr", {"name": "android:fillColor"})
    if gradient.tag == SVG_NS + "linearGradient":
        scale = 1 if gradient.get("gradientUnits") == "userSpaceOnUse" else 1024
        values = {name: float(gradient.get(svg)) * scale for name, svg in
                  (("startX", "x1"), ("startY", "y1"), ("endX", "x2"), ("endY", "y2"))}
        values["type"] = "linear"
    else:
        assert gradient.tag == SVG_NS + "radialGradient"
        values = dict(type="radial", centerX=float(gradient.get("cx")) * 1024,
                      centerY=float(gradient.get("cy")) * 1024,
                      gradientRadius=float(gradient.get("r")) * 1024)
    native = ET.SubElement(item, "gradient", attributes(**values))
    for stop in gradient:
        alpha = round(float(stop.get("stop-opacity", "1")) * 255)
        color = f"#{alpha:02X}" + stop.get("stop-color")[1:]
        ET.SubElement(native, "item", attributes(color=color, offset=stop.get("offset", "0")))


def add_group(parent, source, gradients, monochrome=False):
    """Convert the simple translate/rotate/scale groups used by this artwork."""
    node = parent
    for operation, arguments in re.findall(r"(translate|rotate|scale)\(([^)]+)\)", source.get("transform", "")):
        values = [float(v) for v in re.split(r"[ ,]+", arguments.strip())]
        if operation == "translate":
            values = dict(translateX=values[0], translateY=values[1] if len(values) > 1 else 0)
        elif operation == "rotate":
            assert len(values) == 1
            values = dict(rotation=values[0])
        else:
            values = dict(scaleX=values[0], scaleY=values[-1])
        node = ET.SubElement(node, "group", attributes(**values))
    for child in source:
        if child.tag == SVG_NS + "g":
            add_group(node, child, gradients, monochrome)
            continue
        assert child.tag == SVG_NS + "path"
        values = dict(pathData=" ".join(child.get("d").split()))
        if monochrome:
            values["fillColor"] = "#FFFFFF"
        elif child.get("stroke"):
            values.update(fillColor="#00000000", strokeColor=child.get("stroke"),
                          strokeWidth=child.get("stroke-width"), strokeLineCap="round")
        path = ET.SubElement(node, "path", attributes(**values))
        if not monochrome and child.get("fill", "").startswith("url(#"):
            add_gradient(path, gradients[child.get("fill")[5:-1]])


def main():
    source = ET.parse(ROOT / "design/ami-icon.svg").getroot()
    assert source.get("viewBox") == "0 0 1024 1024"
    gradients = {g.get("id"): g for g in source.find(SVG_NS + "defs") if "Gradient" in g.tag}
    groups = source.findall(SVG_NS + "g")
    assert len(groups) == 2

    background = vector()
    layer = ET.SubElement(background, "group", attributes(translateX=256, translateY=256))
    # Full bleed: the launcher supplies its own mask, with no baked-in corners.
    for name in ("background", "light"):
        path = ET.SubElement(layer, "path", attributes(pathData="M-256,-256H1280V1280H-256Z"))
        add_gradient(path, gradients[name])
    write("drawable/ic_ami_background.xml", background)

    foreground = vector()
    layer = ET.SubElement(foreground, "group", attributes(translateX=256, translateY=256))
    for group in groups:
        add_group(layer, group, gradients)
    write("drawable/ic_ami_foreground.xml", foreground)

    monochrome = vector()
    layer = ET.SubElement(monochrome, "group", attributes(translateX=256, translateY=256))
    # Fine grooves disappear at themed-icon sizes; retain the strong musical mark.
    add_group(layer, groups[-1], gradients, monochrome=True)
    write("drawable/ic_ami_monochrome.xml", monochrome)

    notification = vector(size="24dp", viewport=640)
    layer = ET.SubElement(notification, "group", attributes(translateX=-192, translateY=-192))
    add_group(layer, groups[-1], gradients, monochrome=True)
    write("drawable/ic_music.xml", notification)

    adaptive = ET.Element("adaptive-icon")
    for tag, drawable in (("background", "ic_ami_background"), ("foreground", "ic_ami_foreground"),
                          ("monochrome", "ic_ami_monochrome")):
        ET.SubElement(adaptive, tag, attributes(drawable=f"@drawable/{drawable}"))
    write("mipmap-anydpi-v33/ic_ami.xml", adaptive)


if __name__ == "__main__":
    main()
