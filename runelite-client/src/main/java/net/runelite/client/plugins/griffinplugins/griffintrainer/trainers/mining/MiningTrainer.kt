package net.runelite.client.plugins.griffinplugins.griffintrainer.trainers.mining

import net.runelite.api.ItemID
import net.runelite.api.Player
import net.runelite.api.Skill
import net.runelite.api.coords.WorldArea
import net.runelite.api.coords.WorldPoint
import net.runelite.client.plugins.griffinplugins.griffintrainer.GriffinTrainerConfig
import net.runelite.client.plugins.griffinplugins.griffintrainer.TrainerInterruptor
import net.runelite.client.plugins.griffinplugins.griffintrainer.TrainerThread
import net.runelite.client.plugins.griffinplugins.griffintrainer.helpers.BankHelper
import net.runelite.client.plugins.griffinplugins.griffintrainer.helpers.ItemHelper
import net.runelite.client.plugins.griffinplugins.griffintrainer.itemsets.GeneralItemSets
import net.runelite.client.plugins.griffinplugins.griffintrainer.models.inventory.InventoryRequirements
import net.runelite.client.plugins.griffinplugins.griffintrainer.trainers.BaseTrainer
import net.runelite.client.plugins.griffinplugins.util.helpers.MiningHelper
import net.runelite.client.plugins.griffinplugins.util.helpers.WorldHelper
import net.runelite.client.plugins.microbot.Microbot
import net.runelite.client.plugins.microbot.staticwalker.WorldDestinations
import net.runelite.client.plugins.microbot.util.Global
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank
import net.runelite.client.plugins.microbot.util.inventory.Inventory
import net.runelite.client.plugins.microbot.util.player.Rs2Player

class MiningTrainer(private val config: GriffinTrainerConfig) : BaseTrainer() {
    private val varrockEastMineWorldArea = WorldArea(3281, 3363, 10, 9, 0)
    private val varrockEastMineWorldPoint = WorldPoint(3284, 3366, 0)

    private enum class ScriptState {
        SETUP, CHECKING_AREA, MINING, BANKING
    }

    private var scriptState: ScriptState = ScriptState.SETUP

    override fun getBankLocation(): WorldPoint {
        return WorldDestinations.VARROCK_EAST_BANK.worldPoint
    }

    override fun getInventoryRequirements(): InventoryRequirements {
        val inventoryRequirements = InventoryRequirements()
        val pickaxes = GeneralItemSets.getPickaxesItemSet()
        val helmets = GeneralItemSets.getHelmetItemSet()
        val bodies = GeneralItemSets.getBodiesItemSet()
        val legs = GeneralItemSets.getLegsItemSet()
        val boots = GeneralItemSets.getBootsItemSet()
        val shields = GeneralItemSets.getShieldsItemSet()

        inventoryRequirements.addItemSet(pickaxes)
        inventoryRequirements.addItemSet(helmets)
        inventoryRequirements.addItemSet(bodies)
        inventoryRequirements.addItemSet(legs)
        inventoryRequirements.addItemSet(boots)
        inventoryRequirements.addItemSet(shields)

        return inventoryRequirements
    }

    override fun getMinimumSkillLevel(): Int {
        return Microbot.getClientForKotlin().getRealSkillLevel(Skill.MINING)
    }

    override fun shouldTrain(): Boolean {
        return getMinimumSkillLevel() < config.miningLevel()
    }

    override fun process(): Boolean {
        val minimumSkillLevel = getMinimumSkillLevel()

        if (minimumSkillLevel < 15) {
            updateCounts("Mining Copper", "Copper Mined")
            processState(varrockEastMineWorldArea, varrockEastMineWorldPoint, "copper rocks", ItemID.COPPER_ORE)

        } else if (minimumSkillLevel < 99) {
            updateCounts("Mining Iron", "Iron Mined")
            processState(varrockEastMineWorldArea, varrockEastMineWorldPoint, "iron rocks", ItemID.IRON_ORE)

        } else {
            return true
        }

        return false
    }

    private fun updateCounts(status: String, countLabel: String) {
        if (TrainerThread.countLabel != countLabel) {
            TrainerThread.count = 0
        }

        Microbot.status = status
        TrainerThread.countLabel = countLabel
    }

    fun processState(worldArea: WorldArea, worldPoint: WorldPoint, oreName: String, oreId: Int) {
        when (scriptState) {
            ScriptState.SETUP -> runSetupState()
            ScriptState.CHECKING_AREA -> runCheckingAreaState(worldArea, worldPoint)
            ScriptState.MINING -> runMiningState(oreName, oreId)
            ScriptState.BANKING -> runBankingState()
        }
    }

    private fun runSetupState() {
        if (config.equipGear()) {
            Microbot.getWalkerForKotlin().staticWalkTo(getBankLocation())
            if (!Rs2Bank.isOpen()) {
                Rs2Bank.openBank()
            }

            Rs2Bank.depositAll()
            TrainerInterruptor.sleep(300, 600)
            Rs2Bank.depositEquipment()
            TrainerInterruptor.sleep(600, 900)

            val foundItemIds = BankHelper.fetchInventoryRequirements(getInventoryRequirements())
            Rs2Bank.closeBank()
            TrainerInterruptor.sleepUntilTrue({ !Rs2Bank.isOpen() }, 100, 3000)

            ItemHelper.equipItemIds(foundItemIds)
        }

        scriptState = ScriptState.CHECKING_AREA
    }

    private fun runCheckingAreaState(worldArea: WorldArea, worldPoint: WorldPoint) {
        val player = Microbot.getClientForKotlin().localPlayer
        if (!worldArea.contains(player.worldLocation)) {
            Microbot.getWalkerForKotlin().staticWalkTo(worldPoint)
        }

        if (config.hopWorlds()) {

            val players = Microbot.getClientForKotlin().players
            val playerCount = players
                .filterNotNull()
                .filter { otherPlayer: Player -> otherPlayer.id != player.id }
                .filter { otherPlayer: Player -> worldArea.contains(player.worldLocation) }
                .count()

            if (config.hopWorlds() && playerCount > config.maxPlayers()) {
                WorldHelper.hopToWorldWithoutPlayersInArea(
                    Rs2Player.isMember(),
                    worldArea,
                    config.maxPlayers(),
                    config.maxWorldsToTry()
                )
            }
        }

        scriptState = ScriptState.MINING
    }

    private fun runMiningState(oreName: String, oreId: Int) {
        val beforeCount = Inventory.getInventoryItems().count { item -> item.itemId == oreId }

        if (MiningHelper.findAndMineOre(oreName)) {
            val afterCount = Inventory.getInventoryItems().count { item -> item.itemId == oreId }
            if (afterCount > beforeCount) {
                TrainerThread.count++
            }

            scriptState = ScriptState.BANKING
        }
    }

    private fun runBankingState() {
        if (config.keepOre()) {
            if (Inventory.isFull()) {
                Microbot.getWalkerForKotlin().staticWalkTo(getBankLocation(), 0)
                TrainerInterruptor.sleep(400, 600)

                Rs2Bank.getNearestBank()

                Rs2Bank.openBank()
                if (Rs2Bank.isOpen()) {
                    Rs2Bank.depositAll()
                    Rs2Bank.closeBank()
                }
                TrainerInterruptor.sleep(200)
            }
        } else {
            Inventory.dropAll()
        }

        scriptState = ScriptState.CHECKING_AREA
    }

}