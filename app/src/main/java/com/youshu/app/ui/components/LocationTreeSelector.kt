package com.youshu.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.youshu.app.data.local.entity.Location
import com.youshu.app.ui.theme.PurpleStart
import com.youshu.app.ui.theme.TextHint
import com.youshu.app.ui.theme.TextPrimary

@Composable
fun LocationTreeSelector(
    rootLocations: List<Location>,
    allLocations: List<Location>,
    selectedLocationId: Long?,
    expandedLocations: Map<Long, Boolean>,
    onSelectLocation: (Long) -> Unit,
    onToggleExpand: (Long) -> Unit
) {
    val childrenByParent = remember(allLocations) {
        allLocations
            .groupBy { it.parentId }
            .mapValues { (_, children) -> children.sortedBy { it.name } }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF8F6FC), RoundedCornerShape(18.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (rootLocations.isEmpty()) {
            Text(
                text = "暂无可选位置",
                fontSize = 13.sp,
                color = TextHint,
                modifier = Modifier.padding(10.dp)
            )
        } else {
            rootLocations.forEach { location ->
                LocationTreeNode(
                    location = location,
                    childrenByParent = childrenByParent,
                    selectedLocationId = selectedLocationId,
                    expandedLocations = expandedLocations,
                    onSelectLocation = onSelectLocation,
                    onToggleExpand = onToggleExpand,
                    depth = 0
                )
            }
        }
    }
}

fun expandLocationAncestors(
    locations: List<Location>,
    locationId: Long,
    expandedLocations: MutableMap<Long, Boolean>
) {
    val locationById = locations.associateBy { it.id }
    var current = locationById[locationId]
    current?.let { expandedLocations[it.id] = true }
    while (current?.parentId != null) {
        val parent = locationById[current.parentId] ?: break
        expandedLocations[parent.id] = true
        current = parent
    }
}

@Composable
private fun LocationTreeNode(
    location: Location,
    childrenByParent: Map<Long?, List<Location>>,
    selectedLocationId: Long?,
    expandedLocations: Map<Long, Boolean>,
    onSelectLocation: (Long) -> Unit,
    onToggleExpand: (Long) -> Unit,
    depth: Int
) {
    val children = childrenByParent[location.id].orEmpty()
    val isExpanded = expandedLocations[location.id] ?: false
    val isLeaf = children.isEmpty()
    val selected = selectedLocationId == location.id && isLeaf

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = (depth * 16).dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (selected) PurpleStart.copy(alpha = 0.12f) else Color.Transparent)
                .clickable {
                    if (isLeaf) {
                        onSelectLocation(location.id)
                    } else {
                        onToggleExpand(location.id)
                    }
                }
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (depth == 0) Icons.Default.Home else Icons.Default.LocationOn,
                contentDescription = null,
                tint = if (selected) PurpleStart else TextHint,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = location.name,
                modifier = Modifier.weight(1f),
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = TextPrimary
            )
            if (children.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .clickable { onToggleExpand(location.id) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "收起" else "展开",
                        tint = TextHint,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = isExpanded && children.isNotEmpty(),
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                children.forEach { child ->
                    LocationTreeNode(
                        location = child,
                        childrenByParent = childrenByParent,
                        selectedLocationId = selectedLocationId,
                        expandedLocations = expandedLocations,
                        onSelectLocation = onSelectLocation,
                        onToggleExpand = onToggleExpand,
                        depth = depth + 1
                    )
                }
            }
        }
    }
}
