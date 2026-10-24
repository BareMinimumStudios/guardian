package com.bareminimumstudios.guardian.logging

import kotlin.test.*
import org.junit.jupiter.api.Test

class CommandAuditPolicyTest {
    @Test fun defaultRecordsOnlyRootAndNormalizesNamespaceCase() {
        assertEquals("/give",CommandAuditPolicy.snapshot("give Alex diamond 64"))
        assertEquals("/minecraft:give",CommandAuditPolicy.snapshot(" /Minecraft:Give Alex diamond 64 "))
        assertEquals("/help",CommandAuditPolicy.snapshot("help"))
    }
    @Test fun argumentsRequireSeparateOptIn() {
        assertEquals("/give Alex example:slate 2",CommandAuditPolicy.snapshot("give Alex example:slate 2",true))
        assertEquals("/say snowman ☃",CommandAuditPolicy.snapshot("say snowman ☃",true))
    }
    @Test fun authenticationPrivateAndGuardianRootsAreAlwaysExcluded() {
        for(root in listOf("login","register","changepassword","password","auth","2fa","pin","link","msg","tell","whisper","reply","mail","email","teammsg","guardian","co"))
            for(args in listOf(false,true))for(prefix in listOf("","minecraft:","mod:"))assertNull(CommandAuditPolicy.snapshot("$prefix$root SECRET",args),"$prefix$root")
    }
    @Test fun nestedCommandWrappersNeverRecordArguments() {
        for(root in listOf("execute","function","schedule","return","sudo","runas","silent"))assertEquals("/$root",CommandAuditPolicy.snapshot("$root run login SECRET",true))
    }
    @Test fun extraExclusionsHandleCustomRootsAndNamespaces() {
        assertNull(CommandAuditPolicy.snapshot("custom:signin SECRET",true,"signin"))
        assertNull(CommandAuditPolicy.snapshot("custom:signin SECRET",true," Custom:Signin "))
        assertEquals("/other:signin",CommandAuditPolicy.snapshot("other:signin SECRET",false,"custom:signin"))
    }
    @Test fun invalidOrOversizedExclusionConfigurationFailsClosed() {
        assertNull(CommandAuditPolicy.snapshot("give Alex diamond",true,"bad root"))
        assertNull(CommandAuditPolicy.snapshot("give Alex diamond",true,(1..65).joinToString(","){"root$it"}))
        assertNull(CommandAuditPolicy.snapshot("give Alex diamond",true,"a".repeat(4097)))
    }
    @Test fun boundedArgumentsFallBackToRootWithoutPartialText() {
        assertEquals("/say",CommandAuditPolicy.snapshot("say "+"a".repeat(4092),true))
        assertEquals(4096,CommandAuditPolicy.snapshot("say "+"a".repeat(4091),true)?.length)
        assertNull(CommandAuditPolicy.snapshot("say "+"a".repeat(32769),false))
    }
    @Test fun commandHistoryNavigationKeepsReadOnlyFilters() {
        assertEquals("/guardian commands u:Alex,!Bob t:1h p:2 o:oldest",com.bareminimumstudios.guardian.command.HistoryPageCommands.command("commands","u:Alex,!Bob t:1h p:1 o:newest",2,true))
    }
    @Test fun controlCharactersAndInvalidRootsAreRejected() {
        for(text in listOf("", "/", "say secret\nforged", "say a\tsecret", "say \u0000", "say \u202e spoof", "say \u2028 forged", "bad/root args", "a".repeat(129)+" x"))assertNull(CommandAuditPolicy.snapshot(text,true))
    }
}
