/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settingslib.spa.widget.scaffold

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsSpace
import com.android.settingslib.spa.framework.theme.isSpaExpressiveEnabled
import com.android.settingslib.spa.framework.theme.settingsBackground
import kotlin.math.max
import kotlin.math.roundToInt

private val safeDrawingWindowInsets: WindowInsets
    @Composable
    @NonRestartableComposable
    get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)

@Composable
internal fun CustomizedTopAppBar(
    title: @Composable () -> Unit,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    SingleRowTopAppBar(
        title = title,
        titleTextStyle = MaterialTheme.typography.titleMedium,
        navigationIcon = navigationIcon,
        actions = actions,
        windowInsets = safeDrawingWindowInsets,
        colors = topAppBarColors(),
    )
}

@Composable
internal fun CustomizedTopAppBarWithTitleScalable(
    title: @Composable () -> Unit,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    SingleRowTopAppBar(
        title = title,
        titleTextStyle = MaterialTheme.typography.titleMedium,
        navigationIcon = navigationIcon,
        actions = actions,
        windowInsets = safeDrawingWindowInsets,
        colors = topAppBarColors(),
        titleScaleDisabled = false
    )
}

/**
 * The customized LargeTopAppBar for Settings. Always rendered as a regular single-line toolbar.
 *
 * It never collapses on scroll: the toolbar stays pinned regardless of the page's scroll state.
 */
@Composable
internal fun CustomizedLargeTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    SingleRowTopAppBar(
        title = {
            Title(
                title = title,
                maxLines = 1,
                paddingStart =
                    if (isSpaExpressiveEnabled) 0.dp else SettingsDimension.itemPaddingAround,
            )
        },
        titleTextStyle = MaterialTheme.typography.titleMedium,
        modifier = modifier,
        navigationIcon = navigationIcon,
        actions = actions,
        windowInsets = safeDrawingWindowInsets,
        colors = topAppBarColors(),
    )
}

@Composable
private fun Title(title: String, maxLines: Int = Int.MAX_VALUE, paddingStart: Dp) {
    Text(
        text = title,
        modifier =
            Modifier.padding(start = paddingStart, end = SettingsDimension.itemPaddingEnd)
                .semantics { heading() },
        overflow = TextOverflow.Ellipsis,
        maxLines = maxLines,
    )
}

@Composable
private fun topAppBarColors() =
    if (isSpaExpressiveEnabled) {
        TopAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.primary,
        )
    } else {
        TopAppBarColors(
            containerColor = MaterialTheme.colorScheme.settingsBackground,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

/**
 * Represents the colors used by a top app bar in different states.
 *
 * This implementation animates the container color according to the top app bar scroll state. It
 * does not animate the leading, headline, or trailing colors.
 *
 * @param containerColor the color used for the background of this BottomAppBar. Use
 *   [Color.Transparent] to have no color.
 * @param scrolledContainerColor the container color when content is scrolled behind it
 * @param navigationIconContentColor the content color used for the navigation icon
 * @param titleContentColor the content color used for the title
 * @param actionIconContentColor the content color used for actions
 * @constructor create an instance with arbitrary colors, see [TopAppBarColors] for a factory method
 *   using the default material3 spec
 */
@Stable
internal class TopAppBarColors(
    val containerColor: Color,
    val scrolledContainerColor: Color,
    val navigationIconContentColor: Color,
    val titleContentColor: Color,
    val actionIconContentColor: Color,
) {

    /**
     * Represents the container color used for the top app bar.
     *
     * A [colorTransitionFraction] provides a percentage value that can be used to generate a color.
     * Usually, an app bar implementation will pass in a [colorTransitionFraction] read from the
     * [TopAppBarState.collapsedFraction] or the [TopAppBarState.overlappedFraction].
     *
     * @param colorTransitionFraction a `0.0` to `1.0` value that represents a color transition
     *   percentage
     */
    @Stable
    fun containerColor(colorTransitionFraction: Float): Color {
        return lerp(
            containerColor,
            scrolledContainerColor,
            FastOutLinearInEasing.transform(colorTransitionFraction),
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || other !is TopAppBarColors) return false

        if (containerColor != other.containerColor) return false
        if (scrolledContainerColor != other.scrolledContainerColor) return false
        if (navigationIconContentColor != other.navigationIconContentColor) return false
        if (titleContentColor != other.titleContentColor) return false
        if (actionIconContentColor != other.actionIconContentColor) return false

        return true
    }

    override fun hashCode(): Int {
        var result = containerColor.hashCode()
        result = 31 * result + scrolledContainerColor.hashCode()
        result = 31 * result + navigationIconContentColor.hashCode()
        result = 31 * result + titleContentColor.hashCode()
        result = 31 * result + actionIconContentColor.hashCode()

        return result
    }
}

/**
 * A single-row top app bar that is designed to be called by the small and center aligned top app
 * bar composables.
 */
@Composable
internal fun SingleRowTopAppBar(
    title: @Composable () -> Unit,
    titleTextStyle: TextStyle,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable (RowScope.() -> Unit),
    windowInsets: WindowInsets,
    colors: TopAppBarColors,
    modifier: Modifier = Modifier,
    titleScaleDisabled: Boolean = true,
) {
    // Wrap the given actions in a Row.
    val actionsRow =
        @Composable {
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }

    // Compose a Surface with a TopAppBarLayout content.
    Box(
        modifier =
            modifier
                .drawBehind { drawRect(color = colors.scrolledContainerColor) }
                .semantics { isTraversalGroup = true }
                .pointerInput(Unit) {}
    ) {
        val height = LocalDensity.current.run { ContainerHeight.toPx() }
        TopAppBarLayout(
            modifier =
                Modifier.windowInsetsPadding(windowInsets)
                    // clip after padding so we don't show the title over the inset area
                    .clipToBounds(),
            heightPx = height,
            navigationIconContentColor = colors.navigationIconContentColor,
            titleContentColor = colors.titleContentColor,
            actionIconContentColor = colors.actionIconContentColor,
            title = title,
            titleTextStyle = titleTextStyle,
            titleAlpha = { 1f },
            titleVerticalArrangement = Arrangement.Center,
            titleBottomPadding = 0,
            hideTitleSemantics = false,
            navigationIcon = navigationIcon,
            actions = actionsRow,
            titleScaleDisabled = titleScaleDisabled,
        )
    }
}

/**
 * The base [Layout] for all top app bars. This function lays out a top app bar navigation icon
 * (leading icon), a title (header), and action icons (trailing icons). Note that the navigation and
 * the actions are optional.
 *
 * @param modifier a [Modifier]
 * @param heightPx the total height this layout is capped to
 * @param navigationIconContentColor the content color that will be applied via a
 *   [LocalContentColor] when composing the navigation icon
 * @param titleContentColor the color that will be applied via a [LocalContentColor] when composing
 *   the title
 * @param actionIconContentColor the content color that will be applied via a [LocalContentColor]
 *   when composing the action icons
 * @param title the top app bar title (header)
 * @param titleTextStyle the title's text style
 * @param modifier a [Modifier]
 * @param titleAlpha the title's alpha
 * @param titleVerticalArrangement the title's vertical arrangement
 * @param titleBottomPadding the title's bottom padding
 * @param hideTitleSemantics hides the title node from the semantic tree. Apply this boolean when
 *   this layout is part of a [SingleRowTopAppBar] to hide the title's semantics from accessibility
 *   services.
 * @param navigationIcon a navigation icon [Composable]
 * @param actions actions [Composable]
 * @param titleScaleDisabled whether the title font scaling is disabled. Default is disabled.
 */
@Composable
private fun TopAppBarLayout(
    modifier: Modifier,
    heightPx: Float,
    navigationIconContentColor: Color,
    titleContentColor: Color,
    actionIconContentColor: Color,
    title: @Composable () -> Unit,
    titleTextStyle: TextStyle,
    titleAlpha: () -> Float,
    titleVerticalArrangement: Arrangement.Vertical,
    titleBottomPadding: Int,
    hideTitleSemantics: Boolean,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    titleScaleDisabled: Boolean = true,
) {
    Layout(
        {
            val iconPaddingStart =
                if (isSpaExpressiveEnabled) SettingsSpace.small3 else TopAppBarHorizontalPadding
            Box(Modifier.layoutId("navigationIcon").padding(start = iconPaddingStart)) {
                CompositionLocalProvider(
                    LocalContentColor provides navigationIconContentColor,
                    content = navigationIcon,
                )
            }
            Box(
                Modifier.layoutId("title")
                    .padding(horizontal = TopAppBarHorizontalPadding)
                    .then(if (hideTitleSemantics) Modifier.clearAndSetSemantics {} else Modifier)
                    .graphicsLayer { alpha = titleAlpha() }
            ) {
                ProvideTextStyle(value = titleTextStyle) {
                    CompositionLocalProvider(
                        LocalContentColor provides titleContentColor,
                        LocalDensity provides
                                with(LocalDensity.current) {
                                    Density(
                                        density = density,
                                        fontScale = if (titleScaleDisabled) 1f else fontScale,
                                    )
                                },
                        content = title,
                    )
                }
            }
            Box(Modifier.layoutId("actionIcons").padding(end = TopAppBarHorizontalPadding)) {
                CompositionLocalProvider(
                    LocalContentColor provides actionIconContentColor,
                    content = actions,
                )
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val navigationIconPlaceable =
            measurables
                .first { it.layoutId == "navigationIcon" }
                .measure(constraints.copy(minWidth = 0))
        val actionIconsPlaceable =
            measurables
                .first { it.layoutId == "actionIcons" }
                .measure(constraints.copy(minWidth = 0))

        val maxTitleWidth =
            if (constraints.maxWidth == Constraints.Infinity) {
                constraints.maxWidth
            } else {
                (constraints.maxWidth - navigationIconPlaceable.width - actionIconsPlaceable.width)
                    .coerceAtLeast(0)
            }
        val titlePlaceable =
            measurables
                .first { it.layoutId == "title" }
                .measure(constraints.copy(minWidth = 0, maxWidth = maxTitleWidth))

        // Locate the title's baseline.
        val titleBaseline =
            if (titlePlaceable[LastBaseline] != AlignmentLine.Unspecified) {
                titlePlaceable[LastBaseline]
            } else {
                0
            }

        val layoutHeight = if (heightPx > 0) heightPx.roundToInt() else 0

        layout(constraints.maxWidth, layoutHeight) {
            // Navigation icon
            navigationIconPlaceable.placeRelative(
                x = 0,
                y = (layoutHeight - navigationIconPlaceable.height) / 2,
            )

            // Title
            titlePlaceable.placeRelative(
                x = max(TopAppBarTitleInset.roundToPx(), navigationIconPlaceable.width),
                y =
                    when (titleVerticalArrangement) {
                        Arrangement.Center -> (layoutHeight - titlePlaceable.height) / 2
                        // Apply bottom padding from the title's baseline only when the Arrangement
                        // is "Bottom".
                        Arrangement.Bottom ->
                            if (titleBottomPadding == 0) layoutHeight - titlePlaceable.height
                            else
                                layoutHeight -
                                    titlePlaceable.height -
                                    max(
                                        0,
                                        titleBottomPadding - titlePlaceable.height + titleBaseline,
                                    )
                        // Arrangement.Top
                        else -> 0
                    },
            )

            // Action icons
            actionIconsPlaceable.placeRelative(
                x = constraints.maxWidth - actionIconsPlaceable.width,
                y = (layoutHeight - actionIconsPlaceable.height) / 2,
            )
        }
    }
}

internal val ContainerHeight = 56.dp
private val TopAppBarHorizontalPadding = 4.dp

// A title inset when the App-Bar is a Medium or Large one. Also used to size a spacer when the
// navigation icon is missing.
private val TopAppBarTitleInset = 16.dp - TopAppBarHorizontalPadding
