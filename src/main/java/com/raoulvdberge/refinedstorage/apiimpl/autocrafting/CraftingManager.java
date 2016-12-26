package com.raoulvdberge.refinedstorage.apiimpl.autocrafting;

import com.raoulvdberge.refinedstorage.api.autocrafting.ICraftingManager;
import com.raoulvdberge.refinedstorage.api.autocrafting.ICraftingPattern;
import com.raoulvdberge.refinedstorage.api.autocrafting.ICraftingPatternContainer;
import com.raoulvdberge.refinedstorage.api.autocrafting.ICraftingPatternProvider;
import com.raoulvdberge.refinedstorage.api.autocrafting.registry.ICraftingTaskFactory;
import com.raoulvdberge.refinedstorage.api.autocrafting.task.ICraftingStep;
import com.raoulvdberge.refinedstorage.api.autocrafting.task.ICraftingTask;
import com.raoulvdberge.refinedstorage.api.network.INetworkMaster;
import com.raoulvdberge.refinedstorage.api.network.INetworkNode;
import com.raoulvdberge.refinedstorage.api.util.IComparer;
import com.raoulvdberge.refinedstorage.api.util.IStackList;
import com.raoulvdberge.refinedstorage.apiimpl.API;
import com.raoulvdberge.refinedstorage.tile.TileController;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

public class CraftingManager implements ICraftingManager {
    private static final String NBT_CRAFTING_TASKS = "CraftingTasks";

    private TileController network;

    private List<ICraftingPattern> patterns = new ArrayList<>();

    private List<ICraftingTask> craftingTasks = new ArrayList<>();
    private List<ICraftingTask> craftingTasksToAdd = new ArrayList<>();
    private List<ICraftingTask> craftingTasksToCancel = new ArrayList<>();
    private List<NBTTagCompound> craftingTasksToRead = new ArrayList<>();

    private int ticks;

    public CraftingManager(TileController network) {
        this.network = network;
    }

    @Override
    public List<ICraftingTask> getTasks() {
        return craftingTasks;
    }

    @Override
    public void add(@Nonnull ICraftingTask task) {
        craftingTasksToAdd.add(task);

        network.markDirty();
    }

    @Override
    public void cancel(@Nonnull ICraftingTask task) {
        craftingTasksToCancel.add(task);

        network.markDirty();
    }

    @Override
    public ICraftingTask create(@Nullable ItemStack stack, ICraftingPattern pattern, int quantity) {
        return API.instance().getCraftingTaskRegistry().get(pattern.getId()).create(network.getNetworkWorld(), network, stack, pattern, quantity, null);
    }

    @Override
    public List<ICraftingPattern> getPatterns() {
        return patterns;
    }

    @Override
    public List<ICraftingPattern> getPatterns(ItemStack pattern, int flags) {
        List<ICraftingPattern> patterns = new ArrayList<>();

        for (ICraftingPattern craftingPattern : getPatterns()) {
            for (ItemStack output : craftingPattern.getOutputs()) {
                if (API.instance().getComparer().isEqual(output, pattern, flags)) {
                    patterns.add(craftingPattern);
                }
            }
        }

        return patterns;
    }

    @Override
    public ICraftingPattern getPattern(ItemStack pattern, int flags) {
        List<ICraftingPattern> patterns = getPatterns(pattern, flags);

        if (patterns.isEmpty()) {
            return null;
        } else if (patterns.size() == 1) {
            return patterns.get(0);
        }

        int highestScore = 0;
        int highestPattern = 0;

        IStackList<ItemStack> itemList = network.getItemStorageCache().getList().getOredicted();

        for (int i = 0; i < patterns.size(); ++i) {
            int score = 0;

            for (ItemStack input : patterns.get(i).getInputs()) {
                if (input != null) {
                    ItemStack stored = itemList.get(input, IComparer.COMPARE_DAMAGE | IComparer.COMPARE_NBT | (patterns.get(i).isOredict() ? IComparer.COMPARE_OREDICT : 0));

                    score += stored != null ? stored.getCount() : 0;
                }
            }

            if (score > highestScore) {
                highestScore = score;
                highestPattern = i;
            }
        }

        return patterns.get(highestPattern);
    }

    @Override
    public void update() {
        if (!craftingTasksToRead.isEmpty()) {
            for (NBTTagCompound tag : craftingTasksToRead) {
                ICraftingTask task = readCraftingTask(network.getNetworkWorld(), network, tag);

                if (task != null) {
                    add(task);
                }
            }

            craftingTasksToRead.clear();
        }

        if (network.canRun()) {
            boolean craftingTasksChanged = !craftingTasksToAdd.isEmpty() || !craftingTasksToCancel.isEmpty();

            craftingTasksToCancel.forEach(ICraftingTask::onCancelled);
            craftingTasks.removeAll(craftingTasksToCancel);
            craftingTasksToCancel.clear();

            craftingTasksToAdd.stream().filter(ICraftingTask::isValid).forEach(craftingTasks::add);
            craftingTasksToAdd.clear();

            // Only run task updates every 5 ticks
            if (ticks++ % 5 == 0) {
                Iterator<ICraftingTask> craftingTaskIterator = craftingTasks.iterator();
                Map<ICraftingPatternContainer, Integer> usedCrafters = new HashMap<>();

                while (craftingTaskIterator.hasNext()) {
                    ICraftingTask task = craftingTaskIterator.next();

                    if (task.update(usedCrafters)) {
                        craftingTaskIterator.remove();

                        craftingTasksChanged = true;
                    } else if (!task.getMissing().isEmpty() && ticks % 100 == 0 && Math.random() > 0.5) {
                        task.getMissing().clear();
                    }
                }

                if (craftingTasksChanged) {
                    network.getNetwork().markCraftingMonitorForUpdate();
                }
            }
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        if (tag.hasKey(NBT_CRAFTING_TASKS)) {
            NBTTagList taskList = tag.getTagList(NBT_CRAFTING_TASKS, Constants.NBT.TAG_COMPOUND);

            for (int i = 0; i < taskList.tagCount(); ++i) {
                craftingTasksToRead.add(taskList.getCompoundTagAt(i));
            }
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag) {
        NBTTagList craftingTaskList = new NBTTagList();

        for (ICraftingTask task : craftingTasks) {
            craftingTaskList.appendTag(task.writeToNBT(new NBTTagCompound()));
        }

        tag.setTag(NBT_CRAFTING_TASKS, craftingTaskList);

        return tag;
    }

    @Override
    public ICraftingTask schedule(ItemStack stack, int toSchedule, int compare) {
        for (ICraftingTask task : getTasks()) {
            for (ItemStack output : task.getPattern().getOutputs()) {
                if (API.instance().getComparer().isEqual(output, stack, compare)) {
                    if (task.getPattern().isBlockingTask()) {
                        return null;
                    }
                    toSchedule -= output.getCount() * task.getQuantity();
                }
            }
        }

        if (toSchedule > 0) {
            ICraftingPattern pattern = getPattern(stack, compare);
            if (pattern != null) {
                ICraftingTask task = create(stack, pattern, toSchedule);

                task.calculate();
                task.getMissing().clear();

                add(task);

                network.markCraftingMonitorForUpdate();

                return task;
            }
        }

        return null;
    }

    @Override
    public void track(ItemStack stack, int size) {
        ItemStack inserted = ItemHandlerHelper.copyStackWithSize(stack, size);

        for (ICraftingTask task : craftingTasks) {
            for (ICraftingStep processable : task.getSteps()) {
                if (processable.onReceiveOutput(inserted)) {
                    return;
                }
            }
        }
    }

    @Override
    public void rebuild() {
        patterns.clear();

        for (INetworkNode node : network.getNodeGraph().all()) {
            if (node instanceof ICraftingPatternContainer && node.canUpdate()) {
                patterns.addAll(((ICraftingPatternContainer) node).getPatterns());
            }
        }
    }

    private static ICraftingTask readCraftingTask(World world, INetworkMaster network, NBTTagCompound tag) {
        ItemStack stack = new ItemStack(tag.getCompoundTag(ICraftingTask.NBT_PATTERN_STACK));

        if (!stack.isEmpty() && stack.getItem() instanceof ICraftingPatternProvider) {
            TileEntity container = world.getTileEntity(BlockPos.fromLong(tag.getLong(ICraftingTask.NBT_PATTERN_CONTAINER)));

            if (container instanceof ICraftingPatternContainer) {
                ICraftingPattern pattern = ((ICraftingPatternProvider) stack.getItem()).create(world, stack, (ICraftingPatternContainer) container);

                ICraftingTaskFactory factory = API.instance().getCraftingTaskRegistry().get(tag.getString(ICraftingTask.NBT_PATTERN_ID));

                if (factory != null) {
                    return factory.create(world, network, tag.hasKey(ICraftingTask.NBT_REQUESTED) ? new ItemStack(tag.getCompoundTag(ICraftingTask.NBT_REQUESTED)) : null, pattern, tag.getInteger(ICraftingTask.NBT_QUANTITY), tag);
                }
            }
        }

        return null;
    }
}
