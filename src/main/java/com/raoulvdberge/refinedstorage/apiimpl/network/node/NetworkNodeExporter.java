package com.raoulvdberge.refinedstorage.apiimpl.network.node;

import com.raoulvdberge.refinedstorage.RS;
import com.raoulvdberge.refinedstorage.api.util.IComparer;
import com.raoulvdberge.refinedstorage.inventory.ItemHandlerBase;
import com.raoulvdberge.refinedstorage.inventory.ItemHandlerFluid;
import com.raoulvdberge.refinedstorage.inventory.ItemHandlerListenerNetworkNode;
import com.raoulvdberge.refinedstorage.inventory.ItemHandlerUpgrade;
import com.raoulvdberge.refinedstorage.item.ItemUpgrade;
import com.raoulvdberge.refinedstorage.tile.TileExporter;
import com.raoulvdberge.refinedstorage.tile.config.IComparable;
import com.raoulvdberge.refinedstorage.tile.config.IType;
import com.raoulvdberge.refinedstorage.util.StackUtils;
import com.raoulvdberge.refinedstorage.util.WorldUtils;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;

public class NetworkNodeExporter extends NetworkNode implements IComparable, IType {
    public static final String ID = "exporter";

    private static final String NBT_COMPARE = "Compare";
    private static final String NBT_TYPE = "Type";

    private ItemHandlerBase itemFilters = new ItemHandlerBase(9, new ItemHandlerListenerNetworkNode(this));
    private ItemHandlerFluid fluidFilters = new ItemHandlerFluid(9, new ItemHandlerListenerNetworkNode(this));

    private ItemHandlerUpgrade upgrades = new ItemHandlerUpgrade(4, new ItemHandlerListenerNetworkNode(this), ItemUpgrade.TYPE_SPEED, ItemUpgrade.TYPE_CRAFTING, ItemUpgrade.TYPE_STACK);

    private int compare = IComparer.COMPARE_NBT | IComparer.COMPARE_DAMAGE;
    private int type = IType.ITEMS;

    private int filterSlot;

    public NetworkNodeExporter(World world, BlockPos pos) {
        super(world, pos);
    }

    @Override
    public int getEnergyUsage() {
        return RS.INSTANCE.config.exporterUsage + upgrades.getEnergyUsage();
    }

    @Override
    public void update() {
        super.update();

        if (network != null && canUpdate() && ticks % upgrades.getSpeed() == 0) {
            if (type == IType.ITEMS) {
                IItemHandler handler = WorldUtils.getItemHandler(getFacingTile(), getDirection().getOpposite());

                if (handler != null) {
                    while (filterSlot + 1 < itemFilters.getSlots() && itemFilters.getStackInSlot(filterSlot).isEmpty()) {
                        filterSlot++;
                    }

                    // We jump out of the loop above if we reach the maximum slot. If the maximum slot is empty,
                    // we waste a tick with doing nothing because it's empty. Hence this check. If we are at the last slot
                    // and it's empty, go back to slot 0.
                    // We also handle if we exceeded the maximum slot in general.
                    if ((filterSlot == itemFilters.getSlots() - 1 && itemFilters.getStackInSlot(filterSlot).isEmpty()) || (filterSlot >= itemFilters.getSlots())) {
                        filterSlot = 0;
                    }

                    ItemStack slot = itemFilters.getStackInSlot(filterSlot);

                    if (!slot.isEmpty()) {
                        int stackSize = upgrades.getItemInteractCount();

                        ItemStack took = network.extractItem(slot, Math.min(slot.getMaxStackSize(), stackSize), compare, true);

                        if (took == null) {
                            if (upgrades.hasUpgrade(ItemUpgrade.TYPE_CRAFTING)) {
                                network.getCraftingManager().schedule(slot, stackSize, compare);
                            }
                        } else if (ItemHandlerHelper.insertItem(handler, took, true).isEmpty()) {
                            took = network.extractItem(slot, Math.min(slot.getMaxStackSize(), stackSize), compare, false);

                            if (took != null) {
                                ItemHandlerHelper.insertItem(handler, took, false);
                            }
                        }
                    }

                    filterSlot++;
                }
            } else if (type == IType.FLUIDS) {
                FluidStack[] fluids = fluidFilters.getFluids();

                while (filterSlot + 1 < fluids.length && fluids[filterSlot] == null) {
                    filterSlot++;
                }

                // We jump out of the loop above if we reach the maximum slot. If the maximum slot is empty,
                // we waste a tick with doing nothing because it's empty. Hence this check. If we are at the last slot
                // and it's empty, go back to slot 0.
                // We also handle if we exceeded the maximum slot in general.
                if ((filterSlot == fluids.length - 1 && fluids[filterSlot] == null) || (filterSlot >= fluids.length)) {
                    filterSlot = 0;
                }

                IFluidHandler handler = WorldUtils.getFluidHandler(getFacingTile(), getDirection().getOpposite());

                if (handler != null) {
                    FluidStack stack = fluids[filterSlot];

                    if (stack != null) {
                        FluidStack stackInStorage = network.getFluidStorageCache().getList().get(stack, compare);

                        if (stackInStorage != null) {
                            int toExtract = Math.min(Fluid.BUCKET_VOLUME * upgrades.getItemInteractCount(), stackInStorage.amount);

                            FluidStack took = network.extractFluid(stack, toExtract, compare, true);

                            if (took != null) {
                                int filled = handler.fill(took, false);

                                if (filled > 0) {
                                    took = network.extractFluid(stack, filled, compare, false);

                                    handler.fill(took, true);
                                }
                            }
                        }
                    }

                    filterSlot++;
                }
            }
        }
    }

    @Override
    public int getCompare() {
        return compare;
    }

    @Override
    public void setCompare(int compare) {
        this.compare = compare;

        markDirty();
    }

    @Override
    public void read(NBTTagCompound tag) {
        super.read(tag);

        StackUtils.readItems(upgrades, 1, tag);
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public NBTTagCompound write(NBTTagCompound tag) {
        super.write(tag);

        StackUtils.writeItems(upgrades, 1, tag);

        return tag;
    }

    @Override
    public NBTTagCompound writeConfiguration(NBTTagCompound tag) {
        super.writeConfiguration(tag);

        tag.setInteger(NBT_COMPARE, compare);
        tag.setInteger(NBT_TYPE, type);

        StackUtils.writeItems(itemFilters, 0, tag);
        StackUtils.writeItems(fluidFilters, 2, tag);

        return tag;
    }

    @Override
    public void readConfiguration(NBTTagCompound tag) {
        super.readConfiguration(tag);

        if (tag.hasKey(NBT_COMPARE)) {
            compare = tag.getInteger(NBT_COMPARE);
        }

        if (tag.hasKey(NBT_TYPE)) {
            type = tag.getInteger(NBT_TYPE);
        }

        StackUtils.readItems(itemFilters, 0, tag);
        StackUtils.readItems(fluidFilters, 2, tag);
    }

    public IItemHandler getUpgrades() {
        return upgrades;
    }

    @Override
    public IItemHandler getDrops() {
        return upgrades;
    }

    @Override
    public int getType() {
        return world.isRemote ? TileExporter.TYPE.getValue() : type;
    }

    @Override
    public void setType(int type) {
        this.type = type;

        markDirty();
    }

    @Override
    public IItemHandler getFilterInventory() {
        return getType() == IType.ITEMS ? itemFilters : fluidFilters;
    }
}
