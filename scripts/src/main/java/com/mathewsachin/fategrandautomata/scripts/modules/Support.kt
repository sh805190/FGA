package com.mathewsachin.fategrandautomata.scripts.modules

import com.mathewsachin.fategrandautomata.SupportImageKind
import com.mathewsachin.fategrandautomata.scripts.IFgoAutomataApi
import com.mathewsachin.fategrandautomata.scripts.Images
import com.mathewsachin.fategrandautomata.scripts.ScriptLog
import com.mathewsachin.fategrandautomata.scripts.ScriptNotify
import com.mathewsachin.fategrandautomata.scripts.entrypoints.AutoBattle
import com.mathewsachin.fategrandautomata.scripts.enums.GameServerEnum
import com.mathewsachin.fategrandautomata.scripts.enums.SupportClass
import com.mathewsachin.fategrandautomata.scripts.enums.SupportSelectionModeEnum
import com.mathewsachin.fategrandautomata.scripts.models.SearchFunctionResult
import com.mathewsachin.fategrandautomata.scripts.models.SearchVisibleResult
import com.mathewsachin.libautomata.IPattern
import com.mathewsachin.libautomata.Location
import com.mathewsachin.libautomata.Region
import com.mathewsachin.libautomata.Size
import kotlin.streams.asStream
import kotlin.streams.toList
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private typealias SearchFunction = () -> SearchFunctionResult

const val supportRegionToolSimilarity = 0.75

class Support(
    fgAutomataApi: IFgoAutomataApi
) : IFgoAutomataApi by fgAutomataApi {
    private var preferredServantArray = listOf<String>()
    private var friendNameArray = listOf<String>()
    private var preferredCEArray = listOf<String>()
    private val autoSkillPrefs get() = prefs.selectedBattleConfig.support

    fun init() {
        friendNameArray = autoSkillPrefs.friendNames
        preferredServantArray = autoSkillPrefs.preferredServants
        preferredCEArray = autoSkillPrefs.preferredCEs
    }

    fun selectSupport(SelectionMode: SupportSelectionModeEnum, continuing: Boolean): Boolean {
        waitForSupportScreenToLoad()

        if (!continuing && autoSkillPrefs.supportClass != SupportClass.None) {
            game.locate(autoSkillPrefs.supportClass).click()

            Duration.seconds(0.5).wait()
        }

        return when (SelectionMode) {
            SupportSelectionModeEnum.First -> selectFirst()
            SupportSelectionModeEnum.Manual -> selectManual()
            SupportSelectionModeEnum.Friend -> selectFriend()
            SupportSelectionModeEnum.Preferred -> {
                val searchMethod = decideSearchMethod()
                selectPreferred(searchMethod)
            }
        }
    }

    private fun selectManual(): Boolean {
        throw AutoBattle.BattleExitException(AutoBattle.ExitReason.SupportSelectionManual)
    }

    private var lastSupportRefreshTimestamp: TimeMark? = null
    private val supportRefreshThreshold = Duration.seconds(10)

    private fun refreshSupportList() {
        lastSupportRefreshTimestamp?.elapsedNow()?.let { elapsed ->
            val toWait = supportRefreshThreshold - elapsed

            if (toWait.isPositive()) {
                messages.notify(ScriptNotify.SupportListUpdatingIn(toWait))

                toWait.wait()
            }
        }

        game.supportUpdateClick.click()
        Duration.seconds(1).wait()

        game.supportUpdateYesClick.click()

        waitForSupportScreenToLoad()
        updateLastSupportRefreshTimestamp()
    }

    private fun updateLastSupportRefreshTimestamp() {
        lastSupportRefreshTimestamp = TimeSource.Monotonic.markNow()
    }

    private fun waitForSupportScreenToLoad() {
        while (true) {
            when {
                needsToRetry() -> retry()
                // wait for dialogs to close
                images[Images.SupportExtra] !in game.supportExtraRegion -> Duration.seconds(1).wait()
                images[Images.SupportNotFound] in game.supportNotFoundRegion -> {
                    updateLastSupportRefreshTimestamp()
                    refreshSupportList()
                    return
                }
                game.supportRegionToolSearchRegion.exists(
                    images[Images.SupportRegionTool],
                    similarity = supportRegionToolSimilarity
                ) -> return
                images[Images.Guest] in game.supportFriendRegion -> return
            }
        }
    }

    private fun selectFirst(): Boolean {
        while (true) {
            Duration.seconds(0.5).wait()

            game.supportFirstSupportClick.click()

            // Handle the case of a friend not having set a support servant
            if (game.supportScreenRegion.waitVanish(
                    images[Images.SupportScreen],
                    similarity = 0.85,
                    timeout = Duration.seconds(10)
                )
            ) {
                return true
            }

            refreshSupportList()
        }
    }

    private fun searchVisible(SearchMethod: SearchFunction) =
        useSameSnapIn(fun(): SearchVisibleResult {
            if (!isFriend(game.supportFriendRegion)) {
                // no friends on screen, so there's no point in scrolling anymore
                return SearchVisibleResult.NoFriendsFound
            }

            val result = SearchMethod()

            if (result is SearchFunctionResult.Found) {
                val bounds = when (result) {
                    is SearchFunctionResult.FoundWithBounds -> result.Bounds
                    // bounds are not returned by all methods
                    else -> findSupportBounds(result.Support)
                }

                if (!isFriend(bounds)) {
                    // found something, but it doesn't belong to a friend. keep scrolling
                    return SearchVisibleResult.NotFound
                }

                return SearchVisibleResult.Found(result.Support)
            }

            // nope, not found this time. keep scrolling
            return SearchVisibleResult.NotFound
        })

    private fun selectFriend(): Boolean {
        if (friendNameArray.isNotEmpty()) {
            return selectPreferred { findFriendName() }
        }

        throw AutoBattle.BattleExitException(AutoBattle.ExitReason.SupportSelectionFriendNotSet)
    }

    private fun selectPreferred(SearchMethod: SearchFunction): Boolean {
        var numberOfSwipes = 0
        var numberOfUpdates = 0

        while (true) {
            val result = searchVisible(SearchMethod)

            when {
                result is SearchVisibleResult.Found -> {
                    result.support.click()
                    return true
                }
                result is SearchVisibleResult.NotFound
                        && numberOfSwipes < prefs.support.swipesPerUpdate -> {

                    swipe(
                        game.supportListSwipeStart,
                        game.supportListSwipeEnd
                    )

                    ++numberOfSwipes
                    Duration.seconds(0.3).wait()
                }
                numberOfUpdates < prefs.support.maxUpdates -> {
                    refreshSupportList()

                    ++numberOfUpdates
                    numberOfSwipes = 0
                }
                else -> {
                    // -- okay, we have run out of options, let's give up
                    game.supportListTopClick.click()
                    return selectSupport(autoSkillPrefs.fallbackTo, true)
                }
            }
        }
    }

    data class FoundServantAndCE(val supportBounds: Region, val ce: FoundCE)

    private fun searchServantAndCE(): SearchFunctionResult =
        findServants()
            .mapNotNull {
                val supportBounds = when (it) {
                    is SearchFunctionResult.FoundWithBounds -> it.Bounds
                    else -> findSupportBounds(it.Support)
                }

                val ceBounds = game.supportDefaultCeBounds + Location(0, supportBounds.y)
                findCraftEssences(ceBounds).firstOrNull()
                    ?.let { ce -> FoundServantAndCE(supportBounds, ce) }
            }
            .sortedBy { it.ce }
            .map { SearchFunctionResult.FoundWithBounds(it.ce.region, it.supportBounds) }
            .firstOrNull() ?: SearchFunctionResult.NotFound

    private fun decideSearchMethod(): SearchFunction {
        val hasServants = preferredServantArray.isNotEmpty()
        val hasCraftEssences = preferredCEArray.isNotEmpty()

        return when {
            hasServants && hasCraftEssences -> { -> searchServantAndCE() }
            hasServants -> { -> findServants().firstOrNull() ?: SearchFunctionResult.NotFound }
            hasCraftEssences -> { ->
                findCraftEssences(game.supportListRegion)
                    .map { SearchFunctionResult.Found(it.region) }
                    .firstOrNull() ?: SearchFunctionResult.NotFound
            }
            else -> throw AutoBattle.BattleExitException(AutoBattle.ExitReason.SupportSelectionPreferredNotSet)
        }
    }

    private fun findFriendName(): SearchFunctionResult {
        for (friendName in friendNameArray) {
            // Cached pattern. Don't dispose here.
            val patterns = images.loadSupportPattern(SupportImageKind.Friend, friendName)

            patterns.forEach { pattern ->
                for (theFriend in game.supportFriendsRegion.findAll(pattern).sorted()) {
                    return SearchFunctionResult.Found(theFriend.region)
                }
            }
        }

        return SearchFunctionResult.NotFound
    }

    private fun findServants(): List<SearchFunctionResult.Found> =
        preferredServantArray
            .flatMap { entry -> images.loadSupportPattern(SupportImageKind.Servant, entry) }
            .parallelStream()
            .flatMap { pattern ->
                val needMaxedSkills = listOf(
                    autoSkillPrefs.skill1Max,
                    autoSkillPrefs.skill2Max,
                    autoSkillPrefs.skill3Max
                )
                val skillCheckNeeded = needMaxedSkills.any { it }

                cropFriendLock(pattern).use { cropped ->
                    game.supportListRegion
                        .findAll(cropped)
                        .filter { !autoSkillPrefs.maxAscended || isMaxAscended(it.region) }
                        .map {
                            if (skillCheckNeeded)
                                SearchFunctionResult.FoundWithBounds(it.region, findSupportBounds(it.region))
                            else SearchFunctionResult.Found(it.region)
                        }
                        .filter {
                            it !is SearchFunctionResult.FoundWithBounds || checkMaxedSkills(it.Bounds, needMaxedSkills)
                        }
                        // We want the processing to be finished before cropped pattern is released
                        .toList()
                        .stream()
                }
            }
            .toList()
            .sortedBy { it.Support }

    /**
     * If you lock your friends, a lock icon shows on the left of servant image,
     * which can cause matching to fail.
     *
     * Instead of modifying in-built images and Support Image Maker,
     * which would need everyone to regenerate their images,
     * crop out the part which can potentially have the lock.
     */
    private fun cropFriendLock(servant: IPattern): IPattern {
        val lockCropLeft = 15
        val lockCropRegion = Region(
            lockCropLeft, 0,
            servant.width - lockCropLeft, servant.height
        )
        return servant.crop(lockCropRegion)
    }

    data class FoundCE(val region: Region, val mlb: Boolean) : Comparable<FoundCE> {
        override fun compareTo(other: FoundCE) = when {
            // Prefer MLB
            mlb && !other.mlb -> -1
            !mlb && other.mlb -> 1
            else -> region.compareTo(other.region)
        }
    }

    private fun findCraftEssences(SearchRegion: Region): List<FoundCE> =
        preferredCEArray
            .flatMap { entry -> images.loadSupportPattern(SupportImageKind.CE, entry) }
            .parallelStream()
            .flatMap { pattern ->
                SearchRegion
                    .findAll(pattern)
                    .asStream()
                    .map { FoundCE(it.region, isLimitBroken(it.region)) }
                    .filter { !autoSkillPrefs.mlb || it.mlb }
            }
            .toList()
            .sorted()

    private fun findSupportBounds(Support: Region) =
        game.supportRegionToolSearchRegion
            .findAll(
                images[Images.SupportRegionTool],
                supportRegionToolSimilarity
            )
            .map {
                game.supportDefaultBounds
                    .copy(y = it.region.y - 70)
            }
            .firstOrNull { Support in it }
            ?: game.supportDefaultBounds.also {
                messages.log(ScriptLog.DefaultSupportBounds)
            }

    private fun isFriend(Region: Region): Boolean {
        val onlySelectFriends = autoSkillPrefs.friendsOnly
                || autoSkillPrefs.selectionMode == SupportSelectionModeEnum.Friend

        if (!onlySelectFriends)
            return true

        return sequenceOf(
            images[Images.Friend],
            images[Images.Guest],
            images[Images.Follow]
        ).any { it in Region }
    }

    private fun isStarPresent(region: Region): Boolean {
        val mlbSimilarity = prefs.support.mlbSimilarity
        return region.exists(images[Images.LimitBroken], similarity = mlbSimilarity)
    }

    private fun isMaxAscended(servant: Region): Boolean {
        val maxAscendedRegion = game.supportMaxAscendedRegion
            .copy(y = servant.y)

        return isStarPresent(maxAscendedRegion)
    }

    private fun isLimitBroken(CraftEssence: Region): Boolean {
        val limitBreakRegion = game.supportLimitBreakRegion
            .copy(y = CraftEssence.y)

        return isStarPresent(limitBreakRegion)
    }

    private fun checkMaxedSkills(bounds: Region, needMaxedSkills: List<Boolean>): Boolean {
        val y = bounds.y + 325
        val x = bounds.x + 1620

        val appendSkillsIntroduced = prefs.gameServer == GameServerEnum.Jp
        val skillMargin = if (appendSkillsIntroduced) 90 else 155

        val skillLoc = listOf(
            Location(x, y),
            Location(x + skillMargin, y),
            Location(x + 2 * skillMargin, y)
        )

        val result = skillLoc
            .zip(needMaxedSkills)
            .map { (location, shouldBeMaxed) ->
                if (!shouldBeMaxed)
                    true
                else {
                    val skillRegion = Region(location, Size(50, 50))

                    skillRegion.exists(images[Images.SkillTen], similarity = 0.68)
                }
            }

        messages.log(
            ScriptLog.MaxSkills(
                needMaxedSkills = needMaxedSkills,
                isSkillMaxed = result
            )
        )

        return result.all { it }
    }
}
