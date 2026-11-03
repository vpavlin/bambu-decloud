# Publish to drop (FreeCAD macro)

Selects the active document, exports it, PUTs it to the LAN WebDAV drop, verifies the
served size, and optionally asks the slicer to fetch and slice the same URL.

Install (FreeCAD looks here for macros):

    mkdir -p ~/.local/share/FreeCAD/Macro
    cp PublishToDrop.FCMacro publish_drop.py ~/.local/share/FreeCAD/Macro/

Config, `~/.config/freecad-publish.json` (optional; these are the defaults):

    { "webdav": "http://files.pi5.office.mesh:5000",
      "collection": "projects/models",
      "slicer": "http://slicer.proteus.office.mesh",
      "format": "3mf" }

Use: Macro → Macros… → PublishToDrop.FCMacro, then Macro → Add to toolbar for a button.
From the Python console: `import publish_drop; publish_drop.publish_document("3mf", True)`

Tested without the GUI: `publish_file()` against the real WebDAV, including MKCOL of a
nested collection and the served-size check.
