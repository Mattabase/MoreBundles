package me.salamander.morebundles.mixin;

import me.salamander.morebundles.common.ExtraBundleInfo;
import me.salamander.morebundles.common.items.BundleUtil;
import me.salamander.morebundles.common.items.ItemWithLoot;
import me.salamander.morebundles.common.items.SingleItemBundle;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.client.item.TooltipData;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.loot.LootTable;
import net.minecraft.loot.context.LootContext;
import net.minecraft.nbt.*;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.*;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

@Mixin(BundleItem.class)
public abstract class MixinBundleItem extends Item implements ExtraBundleInfo.Access, ItemWithLoot {

    @Shadow
    private static int getBundleOccupancy(ItemStack stack) {
        throw new AssertionError();
    }

    @Shadow
    public static int addToBundle(ItemStack bundle, ItemStack stack) {
        throw new AssertionError();
    }

    @Shadow
    public static int getItemOccupancy(ItemStack stack) {
        return 0;
    }

    private static final String MAX_BUNDLE_STORAGE_KEY = "MaxBundleStorage";
    private static final String CONTENTS_HIDDEN_KEY = "BundleContentsHidden";

    private final ExtraBundleInfo extraBundleInfo = new ExtraBundleInfo();

    private boolean shouldHideContents = false;

    private MixinBundleItem(Settings settings) {
        super(settings);
    }

    @Override
    public void postProcessNbt(NbtCompound nbt) {
        if(!nbt.contains(MAX_BUNDLE_STORAGE_KEY)) {
            nbt.putInt(MAX_BUNDLE_STORAGE_KEY, extraBundleInfo.getDefaultMaxStorage());
        }

        if(!nbt.contains(CONTENTS_HIDDEN_KEY)){
            nbt.putBoolean(CONTENTS_HIDDEN_KEY, shouldHideContents());
        }
    }

    @Override
    public boolean shouldHideContents() {
        return shouldHideContents;
    }

    @Override
    public void setShouldHideContents(boolean b) {
        shouldHideContents = b;
    }

    @Override
    public ExtraBundleInfo getExtraBundleInfo() {
        return extraBundleInfo;
    }

    @ModifyConstant(method = "getAmountFilled", constant = @Constant(floatValue = 64))
    private static float getAmountFilledOverrideMaxStorage(float value, ItemStack itemStack){
        return BundleUtil.getMaxStorage(itemStack);
    }

    @ModifyConstant(method = "addToBundle", constant = @Constant(intValue = 64))
    private static int getAmountFilledOverrideMaxStorage(int value, ItemStack bundle, ItemStack item){
        return BundleUtil.getMaxStorage(bundle);
    }

    @ModifyConstant(method = {"onStackClicked", "getItemBarStep", "appendTooltip"}, constant = @Constant(intValue = 64))
    private int onStackClickedOverrideMaxStorage(int value, ItemStack stack){
        return BundleUtil.getMaxStorage(stack);
    }

    @Redirect(method = "canMergeStack", at = @At(value = "INVOKE", target = "Ljava/util/stream/Stream;filter(Ljava/util/function/Predicate;)Ljava/util/stream/Stream;"))
    private static Stream mergeAccordingToStackSize(Stream stream, Predicate predicate, ItemStack stack, NbtList items){
        Predicate<NbtCompound> checkOne = (Predicate<NbtCompound>) predicate;
        return stream.filter(checkOne.and((item) -> {
            ItemStack itemStack = ItemStack.fromNbt(item);
            return itemStack.getCount() < itemStack.getItem().getMaxCount();
        }));
    }

    @Redirect(method = "getItemOccupancy", at = @At(value = "INVOKE", target = "Lnet/minecraft/item/ItemStack;isOf(Lnet/minecraft/item/Item;)Z", ordinal = 0))
    private static boolean changeBundleCheckInGetItemOccupancy(ItemStack stack, Item item){
        return stack.getItem() instanceof BundleItem;
    }

    @Inject(method = "addToBundle", at = @At(value = "INVOKE", shift = At.Shift.AFTER, target = "Lnet/minecraft/item/ItemStack;increment(I)V"), locals = LocalCapture.CAPTURE_FAILHARD)
    private static void addPartToBundle(ItemStack bundle, ItemStack stack, CallbackInfoReturnable<Integer> cir, NbtCompound nbtCompound, int i, int j, int k, NbtList nbtList, Optional<NbtCompound> optional, NbtCompound nbtCompound2, ItemStack itemStack){
        int currentAmount = itemStack.getCount();
        int over = currentAmount - itemStack.getMaxCount();
        if(over > 0) {
            itemStack.decrement(over);
            ItemStack restStack = itemStack.copy();
            restStack.setCount(over);
            nbtList.add(0, restStack.writeNbt(new NbtCompound()));
        }
    }

    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void eat(World world, PlayerEntity user, Hand hand, CallbackInfoReturnable<TypedActionResult<ItemStack>> cir){
        if(this.isFood()){
            cir.setReturnValue(super.use(world, user, hand));
        }
    }

    @Inject(method = "addToBundle", at = @At("HEAD"), cancellable = true)
    private static void redirectToSingleItemBundle(ItemStack bundle, ItemStack stack, CallbackInfoReturnable<Integer> cir){
        if(bundle.getItem() instanceof SingleItemBundle){
            cir.setReturnValue(SingleItemBundle.addToBundle(bundle, stack));
        }
    }

    @Inject(method = "getTooltipData", at = @At("HEAD"), cancellable = true)
    private void hideTooltipDataOptionally(ItemStack stack, CallbackInfoReturnable<Optional<TooltipData>> cir){
        if(BundleUtil.shouldHideContents(stack)){
            cir.setReturnValue(Optional.empty());
        }
    }


    @Inject(method = "appendTooltip", at = @At("HEAD"))
    private void sayContentsHidden(ItemStack stack, World world, List<Text> tooltip, TooltipContext context, CallbackInfo ci){
        if(BundleUtil.shouldHideContents(stack) && (getBundleOccupancy(stack) != 0)){
            tooltip.add((new TranslatableText("item.morebundles.bundle.hidden")).formatted(Formatting.DARK_PURPLE));
        }
    }

    @Override
    public void process(ItemStack itemStack, LootContext lootContext) {
        NbtCompound nbt = itemStack.getOrCreateNbt();

        String lootTableName = nbt.getString("LootTable");
        if(lootTableName != null){
            long lootTableSeed = nbt.getLong("LootTableSeed");

            LootTable lootTable = lootContext.getWorld().getServer().getLootManager().getTable(new Identifier(lootTableName));
            List<ItemStack> items = lootTable.generateLoot(lootContext);
            lootContext.getRandom().setSeed(lootContext.getRandom().nextLong() + lootTableSeed);

            if(nbt.getBoolean("Fit")){
                int totalSize = 1;
                for(ItemStack stack: items){
                    totalSize += getItemOccupancy(stack) * stack.getCount();
                }

                nbt.putInt(MAX_BUNDLE_STORAGE_KEY, (int) (totalSize * (1 + lootContext.getRandom().nextFloat() * 0.3f)));
            }

            /*if(!nbt.contains("Items")){
                nbt.put("Items", new NbtList());
            }

            NbtList itemList = nbt.getList("Items", NbtElement.COMPOUND_TYPE);

            for(ItemStack item: items){
                itemList.add(item.writeNbt(new NbtCompound()));
            }*/
            for(ItemStack stack: items){
                addToBundle(itemStack, stack);
            }
        }
    }

    @Override
    public NbtList getItems(ItemStack stack) {
        if(stack == null) return null;

        NbtCompound nbt = stack.getOrCreateNbt();
        if(nbt.contains("Items")){
            return nbt.getList("Items", NbtElement.COMPOUND_TYPE);
        }else{
            NbtList list = new NbtList();
            nbt.put("Items", list);

            return list;
        }
    }

    @Override
    public boolean isEnchantable(ItemStack stack) {
        return true;
    }

    @Override
    public int getEnchantability() {
        return 9;
    }

    @Override
    public Optional<ItemStack> removeFirstStackIf(ItemStack bundle, Predicate<ItemStack> condition) {
        if(bundle.getItem() instanceof SingleItemBundle){
            return SingleItemBundle.removeSingleStackIf(bundle, condition);
        }

        NbtList bundleItems = getItems(bundle);

        for (int i = 0; i < bundleItems.size(); i++) {
            ItemStack stackFromBundle = ItemStack.fromNbt(bundleItems.getCompound(i));
            if (condition.test(stackFromBundle)) {
                bundleItems.remove(i);
                return Optional.of(stackFromBundle);
            }
        }

        return Optional.empty();
    }

    @Inject(method = "getBundleOccupancy", at = @At("HEAD"), cancellable = true)
    private static void getSingleItemBundleOccupancy(ItemStack stack, CallbackInfoReturnable<Integer> cir){
        if(stack.getItem() instanceof SingleItemBundle){
            cir.setReturnValue(SingleItemBundle.getSingleItemBundleOccupancy(stack));
        }
    }

    @Inject(method = "removeFirstStack", at = @At("HEAD"), cancellable = true)
    private static void redirectToSingleItemBundle(ItemStack stack, CallbackInfoReturnable<Optional<ItemStack>> cir){
        if(stack.getItem() instanceof SingleItemBundle) {
            cir.setReturnValue(SingleItemBundle.removeFirstStack(stack));
        }
    }

    @Inject(method = "dropAllBundledItems", at = @At("HEAD"), cancellable = true)
    private static void redirectDropToSingleItemBundle(ItemStack stack, PlayerEntity player, CallbackInfoReturnable<Boolean> cir){
        if(stack.getItem() instanceof SingleItemBundle){
            cir.setReturnValue(SingleItemBundle.dropAllItems(stack, player));
        }
    }
}
