package com.unciv.ui

import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.UnCivGame
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.civilization.CivilizationInfo
import com.unciv.logic.civilization.DiplomaticStatus
import com.unciv.logic.trade.OffersList
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeOffersList
import com.unciv.logic.trade.TradeType
import com.unciv.models.gamebasics.GameBasics
import com.unciv.models.gamebasics.tile.ResourceType
import com.unciv.ui.utils.*
import kotlin.math.max
import kotlin.math.sqrt


data class TradeOffer(var name:String, var type: TradeType, var duration:Int, var amount:Int) {

    constructor() : this("", TradeType.Gold,0,0) // so that the json deserializer can work

    fun equals(offer:TradeOffer): Boolean {
        return offer.name==name
                && offer.type==type
                && offer.amount==amount
    }
}


class TradeScreen(val otherCivilization: CivilizationInfo) : CameraStageBaseScreen(){

    val civInfo = UnCivGame.Current.gameInfo.getPlayerCivilization()
    val ourAvailableOffers = getAvailableOffers(civInfo,otherCivilization)
    val theirAvailableOffers = getAvailableOffers(otherCivilization,civInfo)
    val currentTrade = Trade()

    val table = Table(skin)
    val tradeText = Label("What do you have in mind?".tr(),skin)
    val offerButton = TextButton("Offer trade".tr(),skin)


    val onChange = {
        update()
        offerButton.setText("Offer trade".tr())
        tradeText.setText("What do you have in mind?".tr())
    }

    val ourAvailableOffersTable = OffersList(ourAvailableOffers, currentTrade.ourOffers,
            theirAvailableOffers, currentTrade.theirOffers) { onChange() }
    val ourOffersTable = OffersList(currentTrade.ourOffers, ourAvailableOffers,
            currentTrade.theirOffers, theirAvailableOffers) { onChange() }
    val theirOffersTable = OffersList(currentTrade.theirOffers, theirAvailableOffers,
            currentTrade.ourOffers, ourAvailableOffers) { onChange() }
    val theirAvailableOffersTable = OffersList(theirAvailableOffers, currentTrade.theirOffers,
            ourAvailableOffers, currentTrade.ourOffers) { onChange() }

    init {
        val peaceCost = evaluatePeaceCostForThem()

        val closeButton = TextButton("Close".tr(), skin)
        closeButton.addClickListener { UnCivGame.Current.setWorldScreen() }
        closeButton.y = stage.height - closeButton.height - 5
        stage.addActor(closeButton)
        stage.addActor(table)

        val lowerTable = Table().apply { defaults().pad(10f) }

        lowerTable.add(tradeText).colspan(2).row()

        offerButton.addClickListener {
            if(offerButton.text.toString() == "Offer trade".tr()) {
                if(currentTrade.theirOffers.size==0 && currentTrade.ourOffers.size==0){
                    tradeText.setText("There's nothing on the table.".tr())
                }
                else if (isTradeAcceptable(currentTrade)){
                    tradeText.setText("That is acceptable.".tr())
                    offerButton.setText("Accept".tr())
                }
                else{
                    tradeText.setText("I think not.".tr())
                }
            }
            else if(offerButton.text.toString() == "Accept".tr()){
                civInfo.diplomacy[otherCivilization.civName]!!.trades.add(currentTrade)
                otherCivilization.diplomacy[civInfo.civName]!!.trades.add(currentTrade.reverse())

                // instant transfers
                fun transfer(us: CivilizationInfo,them:CivilizationInfo, trade: Trade) {
                    for (offer in trade.theirOffers) {
                        if (offer.type == TradeType.Gold) {
                            us.gold += offer.amount
                            them.gold -= offer.amount
                        }
                        if (offer.type == TradeType.Technology) {
                            us.tech.techsResearched.add(offer.name)
                        }
                        if(offer.type==TradeType.City){
                            val city = them.cities.first { it.name==offer.name }
                            city.moveToCiv(us)
                            city.getCenterTile().getUnits().forEach { it.movementAlgs().teleportToClosestMoveableTile() }
                        }
                        if(offer.type==TradeType.Treaty){
                            if(offer.name=="Peace Treaty"){
                                us.diplomacy[them.civName]!!.diplomaticStatus=DiplomaticStatus.Peace
                                for(unit in us.getCivUnits().filter { it.getTile().getOwner()==them })
                                    unit.movementAlgs().teleportToClosestMoveableTile()
                            }
                        }
                    }
                }

                transfer(civInfo,otherCivilization,currentTrade)
                transfer(otherCivilization,civInfo,currentTrade.reverse())

                val newTradeScreen = TradeScreen(otherCivilization)
                newTradeScreen.tradeText.setText("Pleasure doing business with you!".tr())
                UnCivGame.Current.screen = newTradeScreen

            }
        }

        lowerTable.add(offerButton)

        lowerTable.pack()
        lowerTable.centerX(stage)
        lowerTable.y = 10f
        stage.addActor(lowerTable)


        table.add("Our items".tr())
        table.add("Our trade offer".tr())
        table.add("[${otherCivilization.civName}]'s trade offer".tr())
        table.add("[${otherCivilization.civName}]'s items".tr()).row()
        table.add(ourAvailableOffersTable).size(stage.width/4,stage.width/2)
        table.add(ourOffersTable).size(stage.width/4,stage.width/2)
        table.add(theirOffersTable).size(stage.width/4,stage.width/2)
        table.add(theirAvailableOffersTable).size(stage.width/4,stage.width/2)
        table.pack()
        table.center(stage)

        update()
    }

    fun update(){
        ourAvailableOffersTable.update()
        ourOffersTable.update()
        theirAvailableOffersTable.update()
        theirOffersTable.update()
    }

    fun getAvailableOffers(civInfo: CivilizationInfo, otherCivilization: CivilizationInfo): TradeOffersList {
        val offers = TradeOffersList()
        if(civInfo.isAtWarWith(otherCivilization))
            offers.add(TradeOffer("Peace Treaty",TradeType.Treaty,20,1))
        for(entry in civInfo.getCivResources().filterNot { it.key.resourceType == ResourceType.Bonus }) {
            val resourceTradeType = if(entry.key.resourceType==ResourceType.Luxury) TradeType.Luxury_Resource
                else TradeType.Strategic_Resource
            offers.add(TradeOffer(entry.key.name, resourceTradeType, 30, entry.value))
        }
        for(entry in civInfo.tech.techsResearched
                .filterNot { otherCivilization.tech.isResearched(it) }
                .filter { otherCivilization.tech.canBeResearched(it) }){
            offers.add(TradeOffer(entry, TradeType.Technology,0,1))
        }
        offers.add(TradeOffer("Gold".tr(), TradeType.Gold,0,civInfo.gold))
        offers.add(TradeOffer("Gold per turn".tr(), TradeType.Gold_Per_Turn,30,civInfo.getStatsForNextTurn().gold.toInt()))
        for(city in civInfo.cities.filterNot { it.isCapital() })
            offers.add(TradeOffer(city.name,TradeType.City,0,1))
        return offers
    }

    fun isTradeAcceptable(trade: Trade): Boolean {
        val sumOfTheirOffers = trade.theirOffers.filter { it.type!=TradeType.Treaty } // since treaties should only be evaluated once for 2 sides
                .map { evaluateOffer(it,false) }.sum()
        val sumOfOurOffers = trade.ourOffers.map { evaluateOffer(it,true)}.sum()
        return sumOfOurOffers >= sumOfTheirOffers
    }

    fun evaluateOffer(offer:TradeOffer, otherCivIsRecieving:Boolean): Int {
        when(offer.type) {
            TradeType.Gold -> return offer.amount
            TradeType.Gold_Per_Turn -> return offer.amount*offer.duration
            TradeType.Luxury_Resource -> {
                if(!otherCivIsRecieving){ // they're giving us
                    var value = 300*offer.amount
                    if(!theirAvailableOffers.any { it.name==offer.name }) // We want to take away their last luxury or give them one they don't have
                        value += 400
                    return value
                }
                else{
                    var value = 50*offer.amount // they'll buy at 50 each only, and that's so they can trade it away
                    if(!theirAvailableOffers.any { it.name==offer.name })
                        value+=250 // only if they're lacking will they buy the first one at 300
                    return value
                }

            }
            TradeType.Technology -> return sqrt(GameBasics.Technologies[offer.name]!!.cost.toDouble()).toInt()*10
            TradeType.Strategic_Resource -> return 50 * offer.amount
            TradeType.City -> {
                val civ = if(otherCivIsRecieving) civInfo else otherCivilization
                val city = civ.cities.first { it.name==offer.name }
                val stats = city.cityStats.currentCityStats
                val sumOfStats = stats.culture+stats.gold+stats.science+stats.production+stats.happiness+stats.food
                return sumOfStats.toInt() * 100
            }
            TradeType.Treaty -> {
                if(offer.name=="Peace Treaty")
                    return evaluatePeaceCostForThem() // Since it will be evaluated twice, once when they evaluate our offer and once when they evaluate theirs
                else return 1000
            }
            // Dunno what this is?
            else -> return 1000
        }
    }

    fun evaluteCombatStrength(civInfo: CivilizationInfo): Int {
        // Since units become exponentially stronger per combat strength increase, we square em all
        fun square(x:Int) = x*x
        val unitStrength =  civInfo.getCivUnits().map { square(max(it.getBaseUnit().strength,it.getBaseUnit().rangedStrength)) }.sum()
        val cityStrength = civInfo.cities.map { square(CityCombatant(it).getCityStrength()) }.sum()
        return (sqrt(unitStrength.toDouble()) /*+ sqrt(cityStrength.toDouble())*/).toInt()
    }

    fun evaluatePeaceCostForThem(): Int {
        val ourCombatStrength = evaluteCombatStrength(civInfo)
        val theirCombatStrength = evaluteCombatStrength(otherCivilization)
        if(ourCombatStrength==theirCombatStrength) return 0
        if(ourCombatStrength==0) return 1000
        if(theirCombatStrength==0) return -1000 // Chumps got no cities or units
        if(ourCombatStrength>theirCombatStrength){
            val absoluteAdvantage = ourCombatStrength-theirCombatStrength
            val percentageAdvantage = absoluteAdvantage / theirCombatStrength.toFloat()
            return (absoluteAdvantage*percentageAdvantage).toInt() * 10
        }
        else{
            val absoluteAdvantage = theirCombatStrength-ourCombatStrength
            val percentageAdvantage = absoluteAdvantage / ourCombatStrength.toFloat()
            return -(absoluteAdvantage*percentageAdvantage).toInt() * 10
        }
    }
}

