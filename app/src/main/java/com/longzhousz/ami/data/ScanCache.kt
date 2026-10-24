package com.longzhousz.ami.data

import com.longzhousz.ami.core.SourceStamp
import org.json.JSONArray
import java.io.File

internal fun DocumentEntry.stamp() = SourceStamp(JSONArray(listOf(uri.toString(), folder, relative)).toString(), modified, size)
internal fun ScanRecord.reusable(fingerprint: String?, present: Set<String>, checkedFiles: MutableMap<String, Boolean>): Boolean =
    fingerprint != null && this.fingerprint == fingerprint && ids.isNotEmpty() && ids.all { it in present } &&
        artifacts.all { checkedFiles.getOrPut(it) { File(it).isFile } }
