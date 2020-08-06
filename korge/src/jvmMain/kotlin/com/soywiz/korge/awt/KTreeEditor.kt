package com.soywiz.korge.awt

import com.soywiz.kds.iterators.*
import com.soywiz.kmem.*
import com.soywiz.korev.*
import com.soywiz.korge.debug.*
import com.soywiz.korge.input.*
import com.soywiz.korge.scene.*
import com.soywiz.korge.view.*
import com.soywiz.korge.view.ktree.*
import com.soywiz.korgw.*
import com.soywiz.korio.async.*
import com.soywiz.korio.file.*
import com.soywiz.korma.geom.*


enum class AnchorKind { SCALING, ROTATING }

data class AnchorPointResult(
    val anchor: Anchor,
    val angle: Angle,
    val kind: AnchorKind
) {
    companion object {
        val ANCHOR_POINT_TO_ANGLE = mapOf(
            Anchor.MIDDLE_RIGHT to ((45).degrees * 0),
            Anchor.BOTTOM_RIGHT to ((45).degrees * 1),
            Anchor.BOTTOM_CENTER to ((45).degrees * 2),
            Anchor.BOTTOM_LEFT to ((45).degrees * 3),
            Anchor.MIDDLE_LEFT to ((45).degrees * 4),
            Anchor.TOP_LEFT to ((45).degrees * 5),
            Anchor.TOP_CENTER to ((45).degrees * 6),
            Anchor.TOP_RIGHT to ((45).degrees * 7),
        )
    }
}

abstract class EditorModule() : Module() {
    open val editableNode: EditableNode? = null
}

suspend fun ktreeEditor(file: VfsFile): EditorModule {
    return myComponentFactory.createModule {
        views.name = "ktree"
        var save = false
        val views = this.views
        val gameWindow = this.views.gameWindow
        val stage = views.stage
        val viewTree = file.readKTree(views)
        val currentVfs = file.parent
        views.currentVfs = currentVfs
        val viewsDebuggerComponent = injector.get<ViewsDebuggerComponent>()
        val actions = viewsDebuggerComponent.actions

        fun getScaleAnchorPoint(view: View?, distance: Int, kind: AnchorKind): AnchorPointResult? {
            if (view == null) return null
            fun cursorDistanceToPoint(point: Point) = (stage.mouseXY - point).length
            var anchor: Anchor? = null
            var angle: Angle? = null

            for ((currentAnchor, currentAngle) in AnchorPointResult.ANCHOR_POINT_TO_ANGLE) {
                if (cursorDistanceToPoint(view.globalLocalBoundsPointRatio(currentAnchor)) < distance) {
                    anchor = currentAnchor
                    angle = (view.rotation + currentAngle).normalized
                    break
                }
            }

            return if (anchor == null || angle == null) null else AnchorPointResult(anchor, angle, kind)
        }

        // Dirty hack
        views.stage.removeChildren()

        if (viewTree is Container) {
            viewTree.children.toList().fastForEach {
                //println("ADDING: $it")
                stage.addChild(it)
            }
        }

        views.debugSavedHandlers.add {
            save = true
        }
        actions.selectView(stage)

        var pressing = false
        val startSelectedViewPos = Point()
        val startSelectedMousePos = Point()
        val selectedViewSize = Size()
        var selectedViewInitialRotation = 0.degrees
        var currentAnchor: AnchorPointResult? = null
        var gridSnapping = true
        var gridWidth = 20.0
        var gridHeight = 20.0

        stage.keys {
            downNew { e ->
                when (e.key) {
                    Key.UP -> actions.moveView(0, -1, e.shift)
                    Key.DOWN -> actions.moveView(0, +1, e.shift)
                    Key.LEFT -> actions.moveView(-1, 0, e.shift)
                    Key.RIGHT -> actions.moveView(+1, 0, e.shift)
                }
            }
            upNew { e ->
                val view = actions.selectedView
                if (view != null) {
                    when (e.key) {
                        Key.DELETE -> actions.removeCurrentNode()
                        Key.BACKSPACE -> actions.removeCurrentNode()
                        Key.D -> if (e.ctrlOrMeta) launchImmediately { actions.duplicate() }
                        Key.X -> if (e.ctrlOrMeta) launchImmediately { actions.cut() }
                        Key.C -> if (e.ctrlOrMeta) launchImmediately { actions.copy() }
                        Key.V -> if (e.ctrlOrMeta) launchImmediately { actions.paste() }
                    }
                }
            }
        }

        stage.mouse {
            click {
                val view = actions.selectedView
                if (it.button == MouseButton.RIGHT) {
                    val hasView = view != null
                    gameWindow.showContextMenu(listOf(
                        GameWindow.MenuItem("Cut", enabled = hasView) { launchImmediately { actions.cut() } },
                        GameWindow.MenuItem("Copy", enabled = hasView) { launchImmediately { actions.copy() } },
                        GameWindow.MenuItem("Paste") { launchImmediately { actions.paste() } },
                        GameWindow.MenuItem("Duplicate") { launchImmediately { actions.duplicate() } },
                        null,
                        GameWindow.MenuItem("Send to back", enabled = hasView) { actions.sendToBack() },
                        GameWindow.MenuItem("Bring to front", enabled = hasView) { actions.sendToFront() },
                    ))
                }
            }
            down {
                pressing = true
                currentAnchor = getScaleAnchorPoint(actions.selectedView, 10, AnchorKind.SCALING)
                    ?: getScaleAnchorPoint(actions.selectedView, 20, AnchorKind.ROTATING)

                startSelectedMousePos.setTo(views.globalMouseX, views.globalMouseY)
                if (currentAnchor == null) {
                    val pickedView = stage.hitTest(it.lastPosStage)
                    val viewLeaf = pickedView.findLastAscendant { it is ViewLeaf }
                    val view = viewLeaf ?: pickedView
                    if (view !== stage) {
                        actions.selectView(view)
                    } else {
                        actions.selectView(null)
                    }
                } else {
                    val view = actions.selectedView
                    if (view != null) {
                        selectedViewSize.setTo(view.scaledWidth, view.scaledHeight)
                        selectedViewInitialRotation = view.rotation
                    }
                }
                actions.selectedView?.let { view ->
                    startSelectedViewPos.setTo(view.globalX, view.globalY)
                }

                //println("DOWN ON ${it.view}, $view2, ${it.lastPosStage}")
            }
            up {
                pressing = false
            }
            onMoveAnywhere { e ->
                val view = actions.selectedView
                if (pressing && view != null) {
                    val dx = views.globalMouseX - startSelectedMousePos.x
                    val dy = views.globalMouseY - startSelectedMousePos.y
                    val anchor = currentAnchor
                    if (anchor != null) {
                        when (anchor.kind) {
                            AnchorKind.SCALING -> {
                                view.scaledWidth = (selectedViewSize.width + dx)
                                view.scaledHeight = (selectedViewSize.height + dy)
                                if (gridSnapping) {
                                    view.scaledWidth = view.scaledWidth.nearestAlignedTo(gridWidth)
                                    view.scaledHeight = view.scaledHeight.nearestAlignedTo(gridHeight)
                                }
                            }
                            AnchorKind.ROTATING -> {
                                val initialAngle = Angle.between(startSelectedViewPos, startSelectedMousePos)
                                val currentAngle = Angle.between(startSelectedViewPos, views.globalMouseXY)
                                val deltaAngle = currentAngle - initialAngle
                                view.rotation = (selectedViewInitialRotation + deltaAngle)
                                if (e.isShiftDown) {
                                    view.rotationDegrees = view.rotationDegrees.nearestAlignedTo(15.0)
                                }
                            }
                        }
                    } else {
                        view.globalX = (startSelectedViewPos.x + dx)
                        view.globalY = (startSelectedViewPos.y + dy)
                        if (gridSnapping) {
                            view.globalX = view.globalX.nearestAlignedTo(gridWidth)
                            view.globalY = view.globalY.nearestAlignedTo(gridHeight)
                        }
                    }
                    save = true
                    //startSelectedViewPos.setTo(view2.globalX, view2.globalY)
                }
            }
        }

        stage.addUpdater {
            gameWindow.cursor =
                com.soywiz.korgw.GameWindow.Cursor.fromAngle(getScaleAnchorPoint(actions.selectedView, 10, AnchorKind.SCALING)?.angle)
                    ?: GameWindow.Cursor.fromAngle(getScaleAnchorPoint(actions.selectedView, 20, AnchorKind.ROTATING)?.angle)?.let { GameWindow.Cursor.CROSSHAIR }
                        ?: GameWindow.Cursor.DEFAULT

            if (save) {
                save = false
                launchImmediately {
                    file.writeString(stage.viewTreeToKTree(views).toOuterXmlIndented().toString())
                }
            }
        }
    }
}
