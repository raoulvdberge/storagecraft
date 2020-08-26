package com.refinedmods.refinedstorage.tile.grid

import com.refinedmods.refinedstorage.RS
import com.refinedmods.refinedstorage.api.network.INetwork
import com.refinedmods.refinedstorage.api.network.grid.*
import com.refinedmods.refinedstorage.api.network.grid.handler.IFluidGridHandler
import com.refinedmods.refinedstorage.api.network.grid.handler.IItemGridHandler
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCache
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCacheListener
import com.refinedmods.refinedstorage.api.util.IFilter
import com.refinedmods.refinedstorage.api.util.IStackList
import com.refinedmods.refinedstorage.apiimpl.storage.cache.listener.ItemGridStorageCacheListener
import com.refinedmods.refinedstorage.inventory.item.BaseItemHandler
import com.refinedmods.refinedstorage.inventory.item.FilterItemHandler
import com.refinedmods.refinedstorage.inventory.listener.InventoryListener
import com.refinedmods.refinedstorage.item.WirelessGridItem
import com.refinedmods.refinedstorage.item.WirelessGridItem.Companion.getViewType
import com.refinedmods.refinedstorage.network.grid.WirelessGridSettingsUpdateMessage
import com.refinedmods.refinedstorage.screen.BaseScreen
import com.refinedmods.refinedstorage.screen.grid.GridScreen
import com.refinedmods.refinedstorage.util.NetworkUtils
import com.refinedmods.refinedstorage.util.StackUtils
import com.refinedmods.refinedstorage.util.StackUtils.readItems
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.entity.player.ServerPlayerEntity
import net.minecraft.inventory.CraftingResultInventory
import net.minecraft.inventory.CraftingInventory
import net.minecraft.item.ItemStack
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.util.RegistryKey
import net.minecraft.util.math.BlockPos
import net.minecraft.util.text.Text
import net.minecraft.util.text.TranslationTextComponent
import net.minecraft.world.World
import net.minecraftforge.items.IItemHandlerModifiable
import java.util.*

class WirelessGrid(val stack: ItemStack, @field:Nullable @param:Nullable private val server: MinecraftServer, slotId: Int) : INetworkAwareGrid {

    private val nodeDimension: RegistryKey<World>
    private val nodePos: BlockPos
    val slotId: Int
    override var viewType: Int
        private set
    override var sortingType: Int
        private set
    override var sortingDirection: Int
        private set
    override var searchBoxMode: Int
        private set
    override var tabSelected: Int
        private set
    private override var tabPage: Int
    override var size: Int
        private set
    override val filters: List<IFilter<*>> = ArrayList()
    override val tabs: List<IGridTab> = ArrayList()
    private override val filter = FilterItemHandler(filters, tabs)
            .addListener(InventoryListener<BaseItemHandler> { handler: BaseItemHandler?, slot: Int, reading: Boolean ->
                if (!stack.hasTag()) {
                    stack.tag = CompoundTag()
                }
                StackUtils.writeItems(handler, 0, stack.tag)
            }) as FilterItemHandler
    override val gridType: GridType
        get() = GridType.NORMAL

    @get:Nullable
    override val network: INetwork?
        get() {
            val world: World? = server.getWorld(nodeDimension)
            return if (world != null) {
                NetworkUtils.getNetworkFromNode(NetworkUtils.getNodeFromTile(world.getBlockEntity(nodePos)))
            } else null
        }

    override fun createListener(player: ServerPlayerEntity?): IStorageCacheListener<*> {
        return ItemGridStorageCacheListener(player, network)
    }

    @get:Nullable
    override val storageCache: IStorageCache<*>?
        get() {
            val network = network
            return network?.itemStorageCache
        }

    @get:Nullable
    override val itemHandler: IItemGridHandler?
        get() {
            val network = network
            return network?.itemGridHandler
        }

    @get:Nullable
    override val fluidHandler: IFluidGridHandler?
        get() = null

    override fun addCraftingListener(listener: ICraftingGridListener?) {
        // NO OP
    }

    override fun removeCraftingListener(listener: ICraftingGridListener?) {
        // NO OP
    }

    override val title: Text
        get() = TranslationTextComponent("gui.refinedstorage.grid")

    override fun getTabPage(): Int {
        return Math.min(tabPage, totalTabPages)
    }

    override val totalTabPages: Int
        get() = Math.floor(Math.max(0, tabs.size - 1).toFloat() / IGrid.TABS_PER_PAGE as Float.toDouble()).toInt()

    override fun onViewTypeChanged(type: Int) {
        RS.NETWORK_HANDLER.sendToServer(WirelessGridSettingsUpdateMessage(type, sortingDirection, sortingType, searchBoxMode, size, tabSelected, getTabPage()))
        viewType = type
        BaseScreen.executeLater(GridScreen::class.java, { grid -> grid.getView().sort() })
    }

    override fun onSortingTypeChanged(type: Int) {
        RS.NETWORK_HANDLER.sendToServer(WirelessGridSettingsUpdateMessage(viewType, sortingDirection, type, searchBoxMode, size, tabSelected, getTabPage()))
        sortingType = type
        BaseScreen.executeLater(GridScreen::class.java, { grid -> grid.getView().sort() })
    }

    override fun onSortingDirectionChanged(direction: Int) {
        RS.NETWORK_HANDLER.sendToServer(WirelessGridSettingsUpdateMessage(viewType, direction, sortingType, searchBoxMode, size, tabSelected, getTabPage()))
        sortingDirection = direction
        BaseScreen.executeLater(GridScreen::class.java, { grid -> grid.getView().sort() })
    }

    override fun onSearchBoxModeChanged(searchBoxMode: Int) {
        RS.NETWORK_HANDLER.sendToServer(WirelessGridSettingsUpdateMessage(viewType, sortingDirection, sortingType, searchBoxMode, size, tabSelected, getTabPage()))
        this.searchBoxMode = searchBoxMode
    }

    override fun onSizeChanged(size: Int) {
        RS.NETWORK_HANDLER.sendToServer(WirelessGridSettingsUpdateMessage(viewType, sortingDirection, sortingType, searchBoxMode, size, tabSelected, getTabPage()))
        this.size = size
        BaseScreen.executeLater(GridScreen::class.java, { obj: BaseScreen<*> -> obj.init() })
    }

    override fun onTabSelectionChanged(tab: Int) {
        tabSelected = if (tab == tabSelected) -1 else tab
        RS.NETWORK_HANDLER.sendToServer(WirelessGridSettingsUpdateMessage(viewType, sortingDirection, sortingType, searchBoxMode, size, tabSelected, getTabPage()))
        BaseScreen.executeLater(GridScreen::class.java, { grid -> grid.getView().sort() })
    }

    override fun onTabPageChanged(page: Int) {
        if (page >= 0 && page <= totalTabPages) {
            RS.NETWORK_HANDLER.sendToServer(WirelessGridSettingsUpdateMessage(viewType, sortingDirection, sortingType, searchBoxMode, size, tabSelected, page))
            tabPage = page
        }
    }

    override fun getFilter(): IItemHandlerModifiable {
        return filter
    }

    override val craftingMatrix: CraftingInventory?
        get() = null
    override val craftingResult: CraftingResultInventory?
        get() = null

    override fun onCraftingMatrixChanged() {
        // NO OP
    }

    override fun onCrafted(player: PlayerEntity?, @Nullable availableItems: IStackList<ItemStack?>?, @Nullable usedItems: IStackList<ItemStack?>?) {
        // NO OP
    }

    override fun onClear(player: PlayerEntity?) {
        // NO OP
    }

    override fun onCraftedShift(player: PlayerEntity?) {
        // NO OP
    }

    override fun onRecipeTransfer(player: PlayerEntity?, recipe: Array<Array<ItemStack?>?>?) {
        // NO OP
    }

    override val isGridActive: Boolean
        get() = true

    override fun onClosed(player: PlayerEntity?) {
        val network = network
        if (network != null) {
            network.networkItemManager!!.close(player)
        }
    }

    init {
        nodeDimension = WirelessGridItem.getDimension(stack)
        nodePos = BlockPos(WirelessGridItem.getX(stack), WirelessGridItem.getY(stack), WirelessGridItem.getZ(stack))
        this.slotId = slotId
        viewType = getViewType(stack)
        sortingType = WirelessGridItem.getSortingType(stack)
        sortingDirection = WirelessGridItem.getSortingDirection(stack)
        searchBoxMode = WirelessGridItem.getSearchBoxMode(stack)
        tabSelected = WirelessGridItem.getTabSelected(stack)
        tabPage = WirelessGridItem.getTabPage(stack)
        size = WirelessGridItem.getSize(stack)
        if (stack.hasTag()) {
            readItems(filter, 0, stack.tag)
        }
    }
}