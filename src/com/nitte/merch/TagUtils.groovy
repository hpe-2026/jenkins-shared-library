package com.nitte.merch

class TagUtils implements Serializable {
    static Map getTagInfo(String tagName) {
        def tag = tagName ?: ''
        def valid = false
        def version = ''
        def isRelease = false
        def prerelease = ''
        
        def matcher = tag =~ /^v(\d+)\.(\d+)\.(\d+)(?:-(.+))?$/
        if (matcher.matches()) {
            valid = true
            version = "${matcher[0][1]}.${matcher[0][2]}.${matcher[0][3]}"
            prerelease = matcher[0][4] ?: ''
            isRelease = (prerelease == null || prerelease == '')
        }
        
        return [tag: tag, valid: valid, version: version, prerelease: prerelease, isRelease: isRelease]
    }
}
