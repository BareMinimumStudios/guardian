package com.bareminimumstudios.guardian.mixin;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Do not inject methods added to Player unless the matching optional provider API exists. */
public final class GuardianOptionalMixinPlugin implements IMixinConfigPlugin {
    private Boolean backpacked;
    private boolean hasBackpackedApi() {
        if (backpacked != null) return backpacked;
        Set<String> methods = new HashSet<>();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(
                "com/mrcrayfish/backpacked/inventory/BackpackedInventoryAccess.class")) {
            if (input == null) return backpacked = false;
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    methods.add(name);
                    switch (name) {
                        case "method_5434" -> methods.add("removeItem");
                        case "method_5441" -> methods.add("removeItemNoUpdate");
                        case "method_5447" -> methods.add("setItem");
                        case "method_7659" -> methods.add("fromTag");
                        default -> { }
                    }
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return backpacked = methods.containsAll(Set.of("backpacked$GetBackpackInventory",
                    "backpacked$GetBackpackInventoryCount", "backpacked$streamNonNullBackpackInventories"));
        } catch (Exception unavailable) { return backpacked = false; }
    }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1).startsWith("Opac")) return hasOpacApi();
        if (mixinClassName.endsWith(".BackpackedOwnershipMixin")) return hasBackpackedApi();
        if (mixinClassName.endsWith(".TrinketsComponentOwnershipMixin")) return hasMethods(targetClassName, Set.of("update", "clearCachedModifiers", "addTemporaryModifiers", "addPersistentModifiers", "removeModifiers", "clearModifiers", "readFromNbt", "applySyncPacket"));
        if (mixinClassName.endsWith(".CuriosInventoryOwnershipMixin")) return hasMethods(targetClassName, Set.of("init", "replace", "deserializeNBT"));
        if (mixinClassName.endsWith(".CuriosSlotsOwnershipMixin")) return hasMethods(targetClassName, Set.of("grow", "shrink", "addLegacyChange", "deserializeNBT", "applySyncTag", "copyModifiers", "addTransientModifier", "addPermanentModifier", "removeModifier", "flagUpdate", "clearModifiers", "clearCachedModifiers", "update", "resize", "loseStacks", "updateActiveState", "deactivateSlot", "activateSlot"));
        if (mixinClassName.endsWith(".ToolBeltOwnershipMixin")) return hasMethods(targetClassName, Set.of("dropContents", "onWornTick", "deserializeNBT", "setContents"));
        if (mixinClassName.endsWith(".TrinketsInventoryOwnershipMixin")) return hasMethods(targetClassName, Set.of("update", "markUpdate", "addModifier", "addPersistentModifier", "removeModifier", "clearModifiers", "removeCachedModifier", "clearCachedModifiers", "fromTag", "applySyncTag", "copyFrom"));
        if (mixinClassName.endsWith(".AccessoriesHolderOwnershipMixin")) return hasMethods(targetClassName, Set.of("init", "read", "setValidTypes"));
        if (mixinClassName.endsWith(".AccessoriesSlotsOwnershipMixin")) return hasMethods(targetClassName, Set.of("update", "markChanged", "read", "copyFrom", "addTransientModifier", "addPersistentModifier", "removeModifier", "clearModifiers", "removeCachedModifiers", "clearCachedModifiers"));
        if (mixinClassName.endsWith(".AccessoriesInventoryOwnershipMixin")) return hasMethods(targetClassName, Set.of("removeItem", "removeItemNoUpdate", "setItem", "fromTag", "setFromPrev", "copyPrev"));
        if (mixinClassName.endsWith(".AccessoriesCapabilityOwnershipMixin")) return hasMethods(targetClassName, Set.of("reset", "updateContainers", "addTransientSlotModifiers", "addPersistentSlotModifiers", "removeSlotModifiers", "clearSlotModifiers", "clearCachedSlotModifiers", "read", "setStack"));
        if (mixinClassName.endsWith(".TravelerEquipmentOwnershipMixin")) return hasMethods(targetClassName, Set.of("getBackpack", "hasBackpack", "getWrapper", "equipBackpack", "updateBackpack", "applyComponents", "removeWearable", "removeWrapper", "remove"));
        if (mixinClassName.endsWith(".TravelerFabricSaveOwnershipMixin")) return hasMethods(targetClassName, Set.of("writeToNbt"));
        if (mixinClassName.endsWith(".TravelerNeoForgeSaveOwnershipMixin")) return hasMethods(targetClassName, Set.of("serializeNBT"));
        if (mixinClassName.endsWith(".TravelerWrapperOwnershipMixin")) return hasMethods(targetClassName, Set.of("getBackpackWrapper"));
        return true;
    }
    private Boolean opac;
    private boolean hasOpacApi() {
        if (opac != null) return opac;
        return opac = opacShape("xaero.pac.common.server.claims.ServerClaimsManager",
                Set.of("claim#6", "tryToClaimHelper#13", "tryAnchor#10", "getServer#0", "getPermissionHandler#0"), false) &&
            opacShape("xaero.pac.common.server.claims.player.request.PlayerClaimActionRequestHandler", Set.of("onReceive#2"), false) &&
            opacShape("xaero.pac.common.server.claims.player.task.PlayerAreaClaimActionSpreadoutTask", Set.of("onQueued#1", "taskBridge"), false) &&
            opacShape("xaero.pac.common.server.claims.player.task.PlayerClaimReplaceSpreadoutTask", Set.of("onQueued#1", "taskBridge", "positionFilter", "replacementOwner", "dimensionKey"), true);
    }
    private boolean opacShape(String target, Set<String> required, boolean code) {
        Set<String> found = new HashSet<>();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(target.replace('.', '/') + ".class")) {
            if (input == null) return false;
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                    if (name.equals("posFilter") && descriptor.equals("Ljava/util/function/BiPredicate;")) found.add("positionFilter");
                    if (name.equals("with") && descriptor.equals("Lxaero/pac/common/claims/player/IPlayerChunkClaim;")) found.add("replacementOwner");
                    return null;
                }
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    if ((access & Opcodes.ACC_STATIC) == 0) found.add(name + "#" + Type.getArgumentTypes(descriptor).length);
                    if (name.equals("onTick") && descriptor.equals("(Lxaero/pac/common/server/IServerData;Ljava/lang/Object;ILjava/util/List;)V")) found.add("taskBridge");
                    if (!code || !name.equals("onTick")) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean itf) {
                            if (owner.equals("java/util/Map$Entry") && method.equals("getKey") && desc.equals("()Ljava/lang/Object;")) found.add("dimensionKey");
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES | (code ? 0 : ClassReader.SKIP_CODE));
            return found.containsAll(required);
        } catch (Exception unavailable) { return false; }
    }
    private boolean hasMethods(String target, Set<String> required) {
        Set<String> methods = new HashSet<>();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(target.replace('.', '/') + ".class")) {
            if (input == null) return false;
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    methods.add(name);
                    switch (name) {
                        case "method_5434" -> methods.add("removeItem");
                        case "method_5441" -> methods.add("removeItemNoUpdate");
                        case "method_5447" -> methods.add("setItem");
                        case "method_7659" -> methods.add("fromTag");
                        default -> { }
                    }
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return methods.containsAll(required);
        } catch (Exception unavailable) { return false; }
    }
    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> mine, Set<String> others) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
}
