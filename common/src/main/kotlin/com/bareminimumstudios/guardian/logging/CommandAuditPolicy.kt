package com.bareminimumstudios.guardian.logging

import java.util.Locale

/** Bounded plain text only. Authentication/private roots are never recorded. */
object CommandAuditPolicy {
    private val rootPattern=Regex("[a-z0-9_.:-]{1,128}")
    private val hiddenTypes=setOf(Character.FORMAT.toInt(),Character.LINE_SEPARATOR.toInt(),Character.PARAGRAPH_SEPARATOR.toInt())
    private val excluded=setOf("guardian","co","login","l","register","reg","changepassword","changepass","password","passwd","unregister","auth","authme","2fa","otp","totp","pin","link","unlink","account","msg","m","message","w","whisper","tell","r","reply","pm","dm","mail","email","teammsg","tm")
    private val opaque=setOf("execute","function","schedule","return","sudo","runas","silent")
    fun snapshot(raw: String, includeArguments: Boolean=false, extraExcludedRoots: String=""): String? {
        if(raw.length>32768 || raw.any { it.isISOControl() || Character.getType(it) in hiddenTypes } || extraExcludedRoots.length>4096)return null
        val additions=extraExcludedRoots.split(',').map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }
        if(additions.size>64 || additions.any { !rootPattern.matches(it) })return null
        val body=raw.trim().removePrefix("/")
        val root=body.substringBefore(' ').lowercase(Locale.ROOT)
        if(!rootPattern.matches(root))return null
        val bare=root.substringAfterLast(':')
        if(bare in excluded || root in additions || bare in additions)return null
        val rootOnly="/$root"
        if(!includeArguments || bare in opaque || body.length>=4096)return rootOnly
        val arguments=body.substringAfter(' ',"").trimStart()
        return if(arguments.isEmpty())rootOnly else "$rootOnly $arguments"
    }
}
