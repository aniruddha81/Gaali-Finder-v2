package com.aniruddha81.gaalifinderv2

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomePage(viewModel: ViewModel) {
    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFFF44336),
                    titleContentColor = Color(0xFFE5D7D7),
                ),
                title = {
                    Text(
                        "Home",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                actions = {
                    IconButton(onClick = { /* Search Action */ }) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = "Search",
                            tint = Color.White
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { /* Add Action */ },
                containerColor = Color(0xFFE53935),
                elevation = FloatingActionButtonDefaults.elevation(8.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "", tint = Color.White)
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(125.dp),
                contentPadding = PaddingValues(8.dp),
                content = {
                    items(30) { i ->
                        Box(
                            modifier = Modifier
                                .padding(5.dp)
                                .fillMaxSize()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(randomMaterial300Color())
                                .padding(12.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                            ) {
                                Text(
                                    text = "Item $i",
                                    color = Color.Black,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold
                                )

                                // Box to allow Row to be aligned at the bottom
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize() // Take up all available space in Column
                                        .padding(bottom = 8.dp)
                                ) {
                                    // Row at the bottom end of the Box
                                    Row(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd) // Align Row at the bottom end
                                            .padding(
                                                end = 8.dp,
                                                bottom = 8.dp
                                            ), // Padding from the right and bottom
                                        horizontalArrangement = Arrangement.spacedBy(8.dp) // Spacing between icons
                                    ) {
                                        // Replacing Icons with IconButtons
                                        IconButton(onClick = { /* Favorite Action */ }) {
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = "Play",
                                                tint = Color.Black
                                            )
                                        }
                                        IconButton(onClick = { /* Share Action */ }) {
                                            Icon(
                                                imageVector = Icons.Default.Share,
                                                contentDescription = "Share",
                                                tint = Color.Black
                                            )
                                        }
                                    }
                                }
                            }

                        }
                    }
                    item{
                        Spacer(Modifier.height(100.dp))
                    }
                }
            )
        }
    }
}

fun randomMaterial300Color(): Color {
    val materialColors300 = listOf(
        Color(0xFFEF9A9A), // Red 300
        Color(0xFFF48FB1), // Pink 300
        Color(0xFF42A5F5), // Blue 300
        Color(0xFF66BB6A), // Green 300
        Color(0xFFFFEE58), // Yellow 300
        Color(0xFFFF7043), // DeepOrange 300
    )
    return materialColors300.random()
}
