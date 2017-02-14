package com.raoulvdberge.refinedstorage;

import com.raoulvdberge.refinedstorage.api.network.INetworkMaster;
import com.raoulvdberge.refinedstorage.api.storage.AccessType;
import com.raoulvdberge.refinedstorage.api.storage.IStorageDisk;
import com.raoulvdberge.refinedstorage.api.storage.IStorageDiskProvider;
import com.raoulvdberge.refinedstorage.api.util.IStackList;
import com.raoulvdberge.refinedstorage.apiimpl.API;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.VertexBuffer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.wrapper.InvWrapper;
import net.minecraftforge.items.wrapper.SidedInvWrapper;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;

public final class RSUtils {
    private static final NonNullList EMPTY_NON_NULL_LIST = new NonNullList<Object>(Collections.emptyList(), null) {
    };

    @SuppressWarnings("unchecked")
    public static <T> NonNullList<T> emptyNonNullList() {
        return (NonNullList<T>) EMPTY_NON_NULL_LIST;
    }

    public static final ItemStack EMPTY_BUCKET = new ItemStack(Items.BUCKET);

    public static final DecimalFormat QUANTITY_FORMATTER = new DecimalFormat("####0.#", DecimalFormatSymbols.getInstance(Locale.US));

    private static final String NBT_INVENTORY = "Inventory_%d";
    private static final String NBT_SLOT = "Slot";
    private static final String NBT_ACCESS_TYPE = "AccessType";

    static {
        QUANTITY_FORMATTER.setRoundingMode(RoundingMode.DOWN);
    }

    public static void writeItemStack(ByteBuf buf, ItemStack stack) {
        writeItemStack(buf, stack, null, false);
    }

    public static void writeItemStack(ByteBuf buf, ItemStack stack, INetworkMaster network, boolean displayCraftText) {
        buf.writeInt(Item.getIdFromItem(stack.getItem()));
        buf.writeInt(stack.getCount());
        buf.writeInt(stack.getItemDamage());
        ByteBufUtils.writeTag(buf, stack.getItem().getNBTShareTag(stack));

        if (network != null) {
            buf.writeInt(API.instance().getItemStackHashCode(stack));
            buf.writeBoolean(network.getCraftingManager().hasPattern(stack));
            buf.writeBoolean(displayCraftText);
        }
    }

    public static ItemStack readItemStack(ByteBuf buf) {
        ItemStack stack = new ItemStack(Item.getItemById(buf.readInt()), buf.readInt(), buf.readInt());
        stack.setTagCompound(ByteBufUtils.readTag(buf));
        return stack;
    }

    public static void writeFluidStack(ByteBuf buf, FluidStack stack) {
        buf.writeInt(API.instance().getFluidStackHashCode(stack));
        ByteBufUtils.writeUTF8String(buf, FluidRegistry.getFluidName(stack.getFluid()));
        buf.writeInt(stack.amount);
        ByteBufUtils.writeTag(buf, stack.tag);
    }

    public static Pair<Integer, FluidStack> readFluidStack(ByteBuf buf) {
        return Pair.of(buf.readInt(), new FluidStack(FluidRegistry.getFluid(ByteBufUtils.readUTF8String(buf)), buf.readInt(), ByteBufUtils.readTag(buf)));
    }

    public static ItemStack transformNullToEmpty(@Nullable ItemStack stack) {
        return stack == null ? ItemStack.EMPTY : stack;
    }

    @Nullable
    public static ItemStack transformEmptyToNull(@Nonnull ItemStack stack) {
        return stack.isEmpty() ? null : stack;
    }

    @SuppressWarnings("unchecked")
    public static void createStorages(ItemStack disk, int slot, IStorageDisk<ItemStack>[] itemStorages, IStorageDisk<FluidStack>[] fluidStorages, Function<IStorageDisk, IStorageDisk> itemStorageWrapper, Function<IStorageDisk, IStorageDisk> fluidStorageWrapper) {
        if (disk.isEmpty()) {
            itemStorages[slot] = null;
            fluidStorages[slot] = null;
        } else {
            IStorageDiskProvider provider = (IStorageDiskProvider) disk.getItem();
            IStorageDisk storage = provider.create(disk);

            storage.readFromNBT();

            switch (storage.getType()) {
                case ITEMS:
                    itemStorages[slot] = itemStorageWrapper.apply(storage);
                    break;
                case FLUIDS:
                    fluidStorages[slot] = fluidStorageWrapper.apply(storage);
                    break;
            }
        }
    }

    public static NonNullList<ItemStack> toNonNullList(List<ItemStack> list) {
        NonNullList<ItemStack> other = NonNullList.create();

        for (ItemStack item : list) {
            if (item != null) {
                other.add(item);
            }
        }

        return other;
    }

    public static void writeItems(IItemHandler handler, int id, NBTTagCompound tag) {
        NBTTagList tagList = new NBTTagList();

        for (int i = 0; i < handler.getSlots(); i++) {
            if (!handler.getStackInSlot(i).isEmpty()) {
                NBTTagCompound stackTag = new NBTTagCompound();

                stackTag.setInteger(NBT_SLOT, i);

                handler.getStackInSlot(i).writeToNBT(stackTag);

                tagList.appendTag(stackTag);
            }
        }

        tag.setTag(String.format(NBT_INVENTORY, id), tagList);
    }

    public static void readItems(IItemHandlerModifiable handler, int id, NBTTagCompound tag) {
        String name = String.format(NBT_INVENTORY, id);

        if (tag.hasKey(name)) {
            NBTTagList tagList = tag.getTagList(name, Constants.NBT.TAG_COMPOUND);

            for (int i = 0; i < tagList.tagCount(); i++) {
                int slot = tagList.getCompoundTagAt(i).getInteger(NBT_SLOT);

                if (slot >= 0 && slot < handler.getSlots()) {
                    handler.setStackInSlot(slot, new ItemStack(tagList.getCompoundTagAt(i)));
                }
            }
        }
    }

    public static void writeItemsLegacy(IInventory inventory, int id, NBTTagCompound tag) {
        NBTTagList tagList = new NBTTagList();

        for (int i = 0; i < inventory.getSizeInventory(); i++) {
            if (!inventory.getStackInSlot(i).isEmpty()) {
                NBTTagCompound stackTag = new NBTTagCompound();

                stackTag.setInteger(NBT_SLOT, i);

                inventory.getStackInSlot(i).writeToNBT(stackTag);

                tagList.appendTag(stackTag);
            }
        }

        tag.setTag(String.format(NBT_INVENTORY, id), tagList);
    }

    public static void readItemsLegacy(IInventory inventory, int id, NBTTagCompound tag) {
        String name = String.format(NBT_INVENTORY, id);

        if (tag.hasKey(name)) {
            NBTTagList tagList = tag.getTagList(name, Constants.NBT.TAG_COMPOUND);

            for (int i = 0; i < tagList.tagCount(); i++) {
                int slot = tagList.getCompoundTagAt(i).getInteger(NBT_SLOT);

                ItemStack stack = new ItemStack(tagList.getCompoundTagAt(i));

                if (!stack.isEmpty()) {
                    inventory.setInventorySlotContents(slot, stack);
                }
            }
        }
    }

    public static NBTTagList serializeFluidStackList(IStackList<FluidStack> list) {
        NBTTagList tagList = new NBTTagList();

        for (FluidStack stack : list.getStacks()) {
            tagList.appendTag(stack.writeToNBT(new NBTTagCompound()));
        }

        return tagList;
    }

    public static IStackList<FluidStack> readFluidStackList(NBTTagList tagList) {
        IStackList<FluidStack> list = API.instance().createFluidStackList();

        for (int i = 0; i < tagList.tagCount(); ++i) {
            FluidStack stack = FluidStack.loadFluidStackFromNBT(tagList.getCompoundTagAt(i));

            if (stack != null) {
                list.add(stack, stack.amount);
            }
        }

        return list;
    }

    public static void writeAccessType(NBTTagCompound tag, AccessType type) {
        tag.setInteger(NBT_ACCESS_TYPE, type.getId());
    }

    public static AccessType readAccessType(NBTTagCompound tag) {
        return tag.hasKey(NBT_ACCESS_TYPE) ? getAccessType(tag.getInteger(NBT_ACCESS_TYPE)) : AccessType.INSERT_EXTRACT;
    }

    public static AccessType getAccessType(int id) {
        for (AccessType type : AccessType.values()) {
            if (type.getId() == id) {
                return type;
            }
        }

        return AccessType.INSERT_EXTRACT;
    }

    public static void updateBlock(World world, BlockPos pos) {
        if (world != null) {
            world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 1 | 2);
        }
    }

    public static IItemHandler getItemHandler(TileEntity tile, EnumFacing side) {
        if (tile == null) {
            return null;
        }

        IItemHandler handler = tile.hasCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, side) ? tile.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, side) : null;

        if (handler == null) {
            if (side != null && tile instanceof ISidedInventory) {
                handler = new SidedInvWrapper((ISidedInventory) tile, side);
            } else if (tile instanceof IInventory) {
                handler = new InvWrapper((IInventory) tile);
            }
        }

        return handler;
    }

    public static IFluidHandler getFluidHandler(TileEntity tile, EnumFacing side) {
        return (tile != null && tile.hasCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, side)) ? tile.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, side) : null;
    }

    public static Pair<ItemStack, FluidStack> getFluidFromStack(ItemStack stack, boolean simulate) {
        if (stack.hasCapability(CapabilityFluidHandler.FLUID_HANDLER_ITEM_CAPABILITY, null)) {
            IFluidHandlerItem fluidHandler = stack.getCapability(CapabilityFluidHandler.FLUID_HANDLER_ITEM_CAPABILITY, null);

            FluidStack result = fluidHandler.drain(Fluid.BUCKET_VOLUME, !simulate);

            return Pair.of(fluidHandler.getContainer(), result);
        }

        return Pair.of(null, null);
    }

    public static boolean hasFluidBucket(FluidStack stack) {
        return stack.getFluid() == FluidRegistry.WATER || stack.getFluid() == FluidRegistry.LAVA || stack.getFluid().getName().equals("milk") || FluidRegistry.getBucketFluids().contains(stack.getFluid());
    }

    public static FluidStack copyStackWithSize(FluidStack stack, int size) {
        FluidStack copy = stack.copy();
        copy.amount = size;
        return copy;
    }

    public static FluidStack copyStack(@Nullable FluidStack stack) {
        return stack == null ? null : stack.copy();
    }

    public static void sendNoPermissionMessage(EntityPlayer player) {
        // When a fake player isn't allowed to do something, we can't send a no permission message because there is no connection associated to the fake player.
        // For example when a Destructor is attempting to break a network block on a secured network with the fake player.
        // So, we first check if there is a connection.
        if (player instanceof EntityPlayerMP && ((EntityPlayerMP) player).connection == null) {
            return;
        }

        player.sendMessage(new TextComponentTranslation("misc.refinedstorage:security.no_permission").setStyle(new Style().setColor(TextFormatting.RED)));
    }

    public static String formatQuantity(int qty) {
        if (qty >= 1000000) {
            return QUANTITY_FORMATTER.format((float) qty / 1000000F) + "M";
        } else if (qty >= 1000) {
            return QUANTITY_FORMATTER.format((float) qty / 1000F) + "K";
        }

        return String.valueOf(qty);
    }

    private static class AdvancedRayTraceResultBase<T extends RayTraceResult> {
        public final AxisAlignedBB bounds;
        public final T hit;

        public AdvancedRayTraceResultBase(T mop, AxisAlignedBB bounds) {

            this.hit = mop;
            this.bounds = bounds;
        }

        public boolean valid() {
            return hit != null && bounds != null;
        }

        public double squareDistanceTo(Vec3d vec) {
            return hit.hitVec.squareDistanceTo(vec);
        }
    }

    public static class AdvancedRayTraceResult extends AdvancedRayTraceResultBase<RayTraceResult> {
        public AdvancedRayTraceResult(RayTraceResult mop, AxisAlignedBB bounds) {
            super(mop, bounds);
        }
    }

    public static Vec3d getStart(EntityPlayer player) {
        return new Vec3d(player.posX, player.posY + player.getEyeHeight(), player.posZ);
    }

    public static Vec3d getEnd(EntityPlayer player) {
        double reachDistance = player instanceof EntityPlayerMP ? ((EntityPlayerMP) player).interactionManager.getBlockReachDistance() : (player.capabilities.isCreativeMode ? 5.0D : 4.5D);

        Vec3d lookVec = player.getLookVec();
        Vec3d start = getStart(player);

        return start.addVector(lookVec.xCoord * reachDistance, lookVec.yCoord * reachDistance, lookVec.zCoord * reachDistance);
    }

    public static AdvancedRayTraceResult collisionRayTrace(BlockPos pos, Vec3d start, Vec3d end, Collection<AxisAlignedBB> boxes) {
        double minDistance = Double.POSITIVE_INFINITY;
        AdvancedRayTraceResult hit = null;
        int i = -1;

        for (AxisAlignedBB aabb : boxes) {
            AdvancedRayTraceResult result = aabb == null ? null : collisionRayTrace(pos, start, end, aabb, i, null);

            if (result != null) {
                double d = result.squareDistanceTo(start);
                if (d < minDistance) {
                    minDistance = d;
                    hit = result;
                }
            }

            i++;
        }

        return hit;
    }

    public static AdvancedRayTraceResult collisionRayTrace(BlockPos pos, Vec3d start, Vec3d end, AxisAlignedBB bounds, int subHit, Object hitInfo) {
        RayTraceResult result = bounds.offset(pos).calculateIntercept(start, end);

        if (result == null) {
            return null;
        }

        result = new RayTraceResult(RayTraceResult.Type.BLOCK, result.hitVec, result.sideHit, pos);
        result.subHit = subHit;
        result.hitInfo = hitInfo;

        return new AdvancedRayTraceResult(result, bounds);
    }

    public static class FluidRenderer {
        private static final int TEX_WIDTH = 16;
        private static final int TEX_HEIGHT = 16;
        private static final int MIN_FLUID_HEIGHT = 1;

        private final int capacityMb;
        private final int width;
        private final int height;

        public FluidRenderer(int capacityMb, int width, int height) {
            this.capacityMb = capacityMb;
            this.width = width;
            this.height = height;
        }

        public void draw(Minecraft minecraft, int xPosition, int yPosition, FluidStack fluidStack) {
            GlStateManager.enableBlend();
            GlStateManager.enableAlpha();

            drawFluid(minecraft, xPosition, yPosition, fluidStack);

            GlStateManager.color(1, 1, 1, 1);

            GlStateManager.disableAlpha();
            GlStateManager.disableBlend();
        }

        private void drawFluid(Minecraft minecraft, int xPosition, int yPosition, FluidStack fluidStack) {
            if (fluidStack == null) {
                return;
            }

            Fluid fluid = fluidStack.getFluid();

            if (fluid == null) {
                return;
            }

            TextureMap textureMapBlocks = minecraft.getTextureMapBlocks();
            ResourceLocation fluidStill = fluid.getStill();
            TextureAtlasSprite fluidStillSprite = null;

            if (fluidStill != null) {
                fluidStillSprite = textureMapBlocks.getTextureExtry(fluidStill.toString());
            }

            if (fluidStillSprite == null) {
                fluidStillSprite = textureMapBlocks.getMissingSprite();
            }

            int fluidColor = fluid.getColor(fluidStack);

            int scaledAmount = height;

            if (capacityMb != -1) {
                scaledAmount = (fluidStack.amount * height) / capacityMb;

                if (fluidStack.amount > 0 && scaledAmount < MIN_FLUID_HEIGHT) {
                    scaledAmount = MIN_FLUID_HEIGHT;
                }

                if (scaledAmount > height) {
                    scaledAmount = height;
                }
            }

            minecraft.renderEngine.bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
            setGLColorFromInt(fluidColor);

            int xTileCount = width / TEX_WIDTH;
            int xRemainder = width - (xTileCount * TEX_WIDTH);
            int yTileCount = scaledAmount / TEX_HEIGHT;
            int yRemainder = scaledAmount - (yTileCount * TEX_HEIGHT);

            int yStart = yPosition + height;

            for (int xTile = 0; xTile <= xTileCount; xTile++) {
                for (int yTile = 0; yTile <= yTileCount; yTile++) {
                    int width = (xTile == xTileCount) ? xRemainder : TEX_WIDTH;
                    int height = (yTile == yTileCount) ? yRemainder : TEX_HEIGHT;
                    int x = xPosition + (xTile * TEX_WIDTH);
                    int y = yStart - ((yTile + 1) * TEX_HEIGHT);

                    if (width > 0 && height > 0) {
                        int maskTop = TEX_HEIGHT - height;
                        int maskRight = TEX_WIDTH - width;

                        drawFluidTexture(x, y, fluidStillSprite, maskTop, maskRight, 100);
                    }
                }
            }
        }
    }

    private static void setGLColorFromInt(int color) {
        float red = (color >> 16 & 0xFF) / 255.0F;
        float green = (color >> 8 & 0xFF) / 255.0F;
        float blue = (color & 0xFF) / 255.0F;

        GlStateManager.color(red, green, blue, 1.0F);
    }

    private static void drawFluidTexture(double xCoord, double yCoord, TextureAtlasSprite textureSprite, int maskTop, int maskRight, double zLevel) {
        double uMin = (double) textureSprite.getMinU();
        double uMax = (double) textureSprite.getMaxU();
        double vMin = (double) textureSprite.getMinV();
        double vMax = (double) textureSprite.getMaxV();
        uMax = uMax - (maskRight / 16.0 * (uMax - uMin));
        vMax = vMax - (maskTop / 16.0 * (vMax - vMin));

        Tessellator tessellator = Tessellator.getInstance();

        VertexBuffer vertexBuffer = tessellator.getBuffer();
        vertexBuffer.begin(7, DefaultVertexFormats.POSITION_TEX);
        vertexBuffer.pos(xCoord, yCoord + 16, zLevel).tex(uMin, vMax).endVertex();
        vertexBuffer.pos(xCoord + 16 - maskRight, yCoord + 16, zLevel).tex(uMax, vMax).endVertex();
        vertexBuffer.pos(xCoord + 16 - maskRight, yCoord + maskTop, zLevel).tex(uMax, vMin).endVertex();
        vertexBuffer.pos(xCoord, yCoord + maskTop, zLevel).tex(uMin, vMin).endVertex();
        tessellator.draw();
    }

    public static <T> Collector<T, ?, NonNullList<T>> toNonNullList() {
        return new Collector<T, NonNullList<T>, NonNullList<T>>() {
            @Override
            public Supplier<NonNullList<T>> supplier() {
                return NonNullList::create;
            }

            @Override
            public BiConsumer<NonNullList<T>, T> accumulator() {
                return (list, item) -> {
                    if (item != null) {
                        list.add(item);
                    }
                };
            }

            @Override
            public BinaryOperator<NonNullList<T>> combiner() {
                return (a, b) -> {
                    NonNullList<T> list = NonNullList.create();
                    a.forEach(list::add);
                    b.forEach(list::add);
                    return list;
                };
            }

            @Override
            public Function<NonNullList<T>, NonNullList<T>> finisher() {
                return (list) -> list;
            }

            @Override
            public Set<Characteristics> characteristics() {
                return EnumSet.of(Characteristics.IDENTITY_FINISH);
            }
        };
    }
}
