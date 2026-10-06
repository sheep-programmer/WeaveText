-- Native system adapter descriptor; no text is uploaded by this package.
local plugin = {}
function plugin.describe()
    return { engine = "apple-system", offline = true, modelManager = "macos-system", minOSVersion = 15 }
end
return plugin
