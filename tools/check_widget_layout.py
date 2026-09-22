#!/usr/bin/env python3
"""Fail the build if a RemoteViews widget layout contains a view the host rejects.

WHY THIS EXISTS
    RemoteViews does not inflate layouts with a normal LayoutInflater. AOSP
    RemoteViews.java installs a filter equivalent to:

        (clazz) -> clazz.isAnnotationPresent(RemoteViews.RemoteView.class)

    Any view in the layout whose class lacks @RemoteViews.RemoteView makes the
    launcher reject the WHOLE RemoteViews -> the widget shows "Can't load widget"
    with no crash and no useful log on the user's phone.

    android.view.View is annotated @UiThread only, so a bare <View> (a common way
    to draw a coloured bar) silently kills the entire widget. That shipped once as
    the state stripe in v3.44 and cost a round trip; LinearLayout, RelativeLayout,
    TextView, Button, ImageView etc. all carry the annotation.

USAGE
    python3 tools/check_widget_layout.py            # check every widget layout
    python3 tools/check_widget_layout.py --self-test # prove the guard catches <View>

Exits 1 and prints offenders when a layout is unsafe.
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
RES = REPO / "app/src/main/res"
API = "{http://schemas.android.com/apk/res/android}"

# Classes carrying @RemoteViews.RemoteView in the framework, plus their full
# package paths. Anything outside this set is rejected at inflate time.
ALLOWED = {
    # layouts / containers
    "FrameLayout", "LinearLayout", "RelativeLayout", "GridLayout", "TableLayout",
    "TableRow", "ViewFlipper", "ViewAnimator", "ViewSwitcher", "AdapterViewFlipper",
    "ListView", "GridView", "StackView", "Space", "RadioGroup",
    # leaves
    "TextView", "Button", "ImageButton", "ImageView", "ProgressBar", "Chronometer",
    "AnalogClock", "TextClock", "RadioButton", "CheckBox", "Switch", "EditText",
    "ScrollView", "HorizontalScrollView", "ViewStub",
}
# The framework verify is annotation-based, but a layout inflated OUTSIDE
# RemoteViews (e.g. previewLayout in the picker) is not bound by it. Only files
# that ship as widgetRemoteViews are checked.
CONTAINERS_ONLY = {"FrameLayout", "LinearLayout", "RelativeLayout"}


def widget_layouts():
    """Layouts referenced as initialLayout in res/xml/widget_*_info.xml."""
    out = []
    for info in sorted((RES / "xml").glob("*widget*_info.xml")):
        txt = info.read_text()
        for tag in ("initialLayout", "previewLayout"):
            key = f'android:{tag}="@layout/'
            i = txt.find(key)
            if i == -1:
                continue
            name = txt[i + len(key):].split('"')[0]
            p = RES / "layout" / f"{name}.xml"
            if p.exists() and tag == "initialLayout":
                out.append((info.name, p))
    # plus any layout the provider codepublishes directly
    for p in sorted((RES / "layout").glob("widget_*.xml")):
        if not any(p == q for _, q in out):
            out.append(("(glob)", p))
    return out


def check(path):
    offenders = []
    root = ET.parse(path).getroot()
    for el in root.iter():
        tag = el.tag.split("}")[-1]
        if tag not in ALLOWED:
            offenders.append(f"{tag} (id={el.get(API + 'id', '?')})")
    return offenders


def main():
    if "--self-test" in sys.argv:
        bad = "<RelativeLayout xmlns:android='http://schemas.android.com/apk/res/android'><View android:id='@+id/x'/></RelativeLayout>"
        tmp = Path("/tmp/_remoteviews_selftest.xml")
        tmp.write_text(bad)
        off = check(tmp)
        print(f"self-test: <View> detected -> {off}")
        assert off, "guard failed to catch <View>"
        print("self-test PASSED (the v3.44 stripe bug would have been caught)")
        return 0

    layouts = widget_layouts()
    if not layouts:
        print("no widget layouts found")
        return 0
    failed = False
    for info, p in layouts:
        off = check(p)
        status = "FAIL" if off else "ok"
        print(f"[{status}] {p.relative_to(REPO)}  (from {info})")
        for o in off:
            failed = True
            print(f"         not @RemoteView: {o}")
    print("\nRemoteViews safety: " + ("FAILED - widget will not load" if failed else "all layouts safe"))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())