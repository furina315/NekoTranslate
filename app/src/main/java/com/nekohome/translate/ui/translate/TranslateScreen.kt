@file:OptIn(ExperimentalMaterial3Api::class)

package com.nekohome.translate.ui.translate

import android.os.SystemClock
import com.nekohome.translate.engine.ImageStage
import kotlinx.coroutines.delay
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nekohome.translate.R
import com.nekohome.translate.data.Language
import com.nekohome.translate.llama.StopReason
import com.nekohome.translate.ui.MainUiState
import com.nekohome.translate.ui.MainViewModel
import com.nekohome.translate.ui.motion.LocalReduceMotion
import com.nekohome.translate.ui.motion.Springs
import com.nekohome.translate.ui.motion.bounceClick
import com.nekohome.translate.ui.motion.motion
import com.nekohome.translate.tts.SpeakTarget
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.StopCircle
import java.util.Locale

@Composable
fun TranslateScreen(
    state: MainUiState,
    vm: MainViewModel,
    showStats: Boolean,
    onOpenModels: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Outlined.History, contentDescription = stringResource(R.string.translate_history))
                    }
                    ModelChip(
                        isLoading = state.isLoading,
                        loadedName = state.loadedModel?.file?.nameWithoutExtension,
                        onClick = onOpenModels,
                    )
                    Spacer(Modifier.width(8.dp))
                },
            )
        },
    ) { padding ->
        // Streaming text is collected here but only *read* inside StreamingText / click handlers,
        // so new tokens recompose just that Text, not this screen.
        val output = vm.output.collectAsStateWithLifecycle()
        val outputProvider = remember(output) { { output.value } }
        val imagePicker = rememberImagePicker(vm)
        val speaking by vm.speaking.collectAsStateWithLifecycle()
        if (state.loaded == null) {
            NoModelState(
                state = state,
                onOpenModels = onOpenModels,
                onLoad = vm::loadModel,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val profile = state.activeProfile
                val sourceOptions = remember(profile) { listOf(Language.AUTO) + profile.languages }
                LanguageBar(
                    source = state.sourceLanguage,
                    target = state.targetLanguage,
                    sourceOptions = sourceOptions,
                    targetOptions = profile.languages,
                    enabled = !state.isTranslating,
                    onSource = vm::setSource,
                    onTarget = vm::setTarget,
                    onSwap = vm::swapLanguages,
                )
                InputCard(
                    input = state.input,
                    isTranslating = state.isTranslating,
                    canTranslate = state.canTranslate,
                    imageSupported = state.imageSupported,
                    imageThumb = state.imageThumb,
                    isPreparingImage = state.isPreparingImage,
                    onInput = vm::setInput,
                    onClear = vm::clearInput,
                    onTranslate = vm::translate,
                    onStop = vm::stop,
                    onCamera = imagePicker.camera,
                    onGallery = imagePicker.gallery,
                    onClearImage = vm::clearImage,
                    speaking = speaking == SpeakTarget.SOURCE,
                    onSpeak = vm::toggleSpeakInput,
                )
                val reduceMotion = LocalReduceMotion.current
                AnimatedVisibility(
                    visible = state.hasOutput || state.isTranslating,
                    // Large card: fade + expand without overshoot (a bouncy scale here re-laid-out
                    // the whole column for several hundred ms).
                    enter = if (reduceMotion) EnterTransition.None else
                        fadeIn(Springs.smooth(Spring.StiffnessMedium)) +
                            expandVertically(Springs.gentle(Spring.StiffnessMedium), expandFrom = Alignment.Top),
                    exit = if (reduceMotion) ExitTransition.None else
                        fadeOut(Springs.smooth(Spring.StiffnessMedium)) +
                            shrinkVertically(Springs.gentle(Spring.StiffnessMedium), shrinkTowards = Alignment.Top),
                ) {
                    OutputCard(
                        targetName = state.targetLanguage.nameZh,
                        isTranslating = state.isTranslating,
                        hasOutput = state.hasOutput,
                        imageStage = state.imageStage,
                        imageStageSinceMs = state.imageStageSinceMs,
                        imageStageBackend = state.imageStageBackend,
                        output = outputProvider,
                        stats = state.stats,
                        showStats = showStats,
                        speaking = speaking == SpeakTarget.OUTPUT,
                        onSpeak = vm::toggleSpeakOutput,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun ModelChip(isLoading: Boolean, loadedName: String?, onClick: () -> Unit) {
    val label = when {
        isLoading -> stringResource(R.string.translate_model_loading)
        loadedName != null -> loadedName
        else -> stringResource(R.string.translate_model_none)
    }
    AssistChip(
        onClick = onClick,
        label = {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
        },
        leadingIcon = {
            if (isLoading) {
                CircularProgressIndicator(Modifier.size(AssistChipDefaults.IconSize), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.Memory, contentDescription = null, Modifier.size(AssistChipDefaults.IconSize))
            }
        },
    )
}

@Composable
private fun NoModelState(
    state: MainUiState,
    onOpenModels: () -> Unit,
    onLoad: (com.nekohome.translate.data.LocalModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.Translate,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.translate_empty_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.translate_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        val quick = state.selectedModel
        if (state.isLoading) {
            CircularProgressIndicator()
        } else if (quick != null) {
            Button(onClick = { onLoad(quick) }) {
                Text(
                    stringResource(R.string.translate_empty_load, quick.file.nameWithoutExtension),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onOpenModels) { Text(stringResource(R.string.translate_empty_go_models)) }
        } else {
            Button(onClick = onOpenModels) { Text(stringResource(R.string.translate_empty_go_models)) }
        }
    }
}

@Composable
private fun LanguageBar(
    source: Language,
    target: Language,
    sourceOptions: List<Language>,
    targetOptions: List<Language>,
    enabled: Boolean,
    onSource: (String) -> Unit,
    onTarget: (String) -> Unit,
    onSwap: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            LanguageButton(
                selected = source,
                options = sourceOptions,
                onSelect = { onSource(it.code) },
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            // Each swap spins the arrows half a turn with a bouncy spring. The angle is read in the
            // graphics layer (draw phase), so the spin never recomposes the bar.
            var turns by rememberSaveable { mutableIntStateOf(0) }
            val rotation = animateFloatAsState(
                targetValue = turns * 180f,
                animationSpec = motion(spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow)),
                label = "swap",
            )
            val swapSource = remember { MutableInteractionSource() }
            IconButton(
                onClick = {
                    turns++
                    onSwap()
                },
                enabled = enabled,
                interactionSource = swapSource,
                modifier = Modifier.bounceClick(swapSource, pressedScale = 0.85f),
            ) {
                Icon(
                    Icons.Filled.SwapHoriz,
                    contentDescription = stringResource(R.string.translate_swap),
                    modifier = Modifier.graphicsLayer { rotationZ = rotation.value },
                )
            }
            LanguageButton(
                selected = target,
                options = targetOptions,
                onSelect = { onTarget(it.code) },
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun LanguageButton(
    selected: Language,
    options: List<Language>,
    onSelect: (Language) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(
                languageLabel(selected),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 420.dp),
        ) {
            options.forEach { lang ->
                DropdownMenuItem(
                    text = {
                        Text(if (lang.isAuto) languageLabel(lang) else "${lang.nameZh}  ·  ${lang.nameEn}")
                    },
                    trailingIcon = {
                        if (lang.code == selected.code) Icon(Icons.Filled.Check, contentDescription = null)
                    },
                    onClick = {
                        expanded = false
                        onSelect(lang)
                    },
                )
            }
        }
    }
}

@Composable
private fun languageLabel(lang: Language): String =
    if (lang.isAuto) stringResource(R.string.translate_auto_detect) else lang.nameZh

/** Camera / gallery launchers for image translation. */
private class ImagePicker(val camera: () -> Unit, val gallery: () -> Unit)

@Composable
private fun rememberImagePicker(vm: MainViewModel): ImagePicker {
    val context = LocalContext.current
    val noCamera = stringResource(R.string.translate_image_no_camera)
    // Survives the activity being recreated while the camera app is in front.
    var pendingCameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        vm.onCameraResult(ok, pendingCameraUri?.let(Uri::parse))
        pendingCameraUri = null
    }
    // System Photo Picker (falls back to the document picker where unavailable); no permission needed.
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.pickImage(uri)
    }
    return remember(vm, takePicture, pickMedia) {
        ImagePicker(
            camera = {
                val uri = vm.cameraCaptureUri()
                pendingCameraUri = uri.toString()
                try {
                    takePicture.launch(uri)
                } catch (e: ActivityNotFoundException) {
                    pendingCameraUri = null
                    Toast.makeText(context, noCamera, Toast.LENGTH_SHORT).show()
                }
            },
            gallery = {
                pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
        )
    }
}

@Composable
private fun InputCard(
    input: String,
    isTranslating: Boolean,
    canTranslate: Boolean,
    imageSupported: Boolean,
    imageThumb: ImageBitmap?,
    isPreparingImage: Boolean,
    onInput: (String) -> Unit,
    onClear: () -> Unit,
    onTranslate: () -> Unit,
    onStop: () -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onClearImage: () -> Unit,
    speaking: Boolean,
    onSpeak: () -> Unit,
) {
    val context = LocalContext.current
    val imageMode = imageThumb != null || isPreparingImage
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        val reduceMotionSource = LocalReduceMotion.current // read outside the transitionSpec lambda
        AnimatedContent(
            targetState = imageMode,
            transitionSpec = {
                if (reduceMotionSource) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    (fadeIn(Springs.smooth(Spring.StiffnessMedium)) + scaleIn(Springs.gentle(Spring.StiffnessMedium), initialScale = 0.96f))
                        .togetherWith(fadeOut(Springs.smooth(Spring.StiffnessMedium)))
                        .using(SizeTransform(clip = true) { _, _ -> Springs.gentle(Spring.StiffnessMedium) })
                }
            },
            label = "sourceMode",
        ) { showImage ->
            if (showImage) {
                SourceImage(
                    thumb = imageThumb,
                    isPreparing = isPreparingImage,
                    clearEnabled = !isTranslating,
                    onClear = onClearImage,
                )
            } else {
                TextField(
                    value = input,
                    onValueChange = onInput,
                    placeholder = { Text(stringResource(R.string.translate_input_hint)) },
                    textStyle = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 4.dp, end = 12.dp, bottom = 12.dp),
        ) {
            if (imageSupported) {
                ImageSourceButton(
                    enabled = !isTranslating && !isPreparingImage,
                    onCamera = onCamera,
                    onGallery = onGallery,
                )
            }
            // In image mode the thumbnail's own close button clears the source.
            if (!imageMode && input.isEmpty()) {
                IconButton(onClick = { pasteText(context)?.let(onInput) }) {
                    Icon(Icons.Outlined.ContentPaste, contentDescription = stringResource(R.string.translate_paste))
                }
            } else if (!imageMode) {
                IconButton(onClick = onClear, enabled = !isTranslating) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.translate_clear))
                }
                SpeakButton(
                    speaking = speaking,
                    enabled = input.isNotBlank(),
                    label = stringResource(R.string.tts_speak_source),
                    onClick = onSpeak,
                )
                Text(
                    input.length.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            val reduceMotion = LocalReduceMotion.current
            AnimatedContent(
                targetState = isTranslating,
                transitionSpec = {
                    if (reduceMotion) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        (scaleIn(Springs.bouncy(), initialScale = 0.8f) + fadeIn(Springs.smooth(Spring.StiffnessMedium)))
                            .togetherWith(
                                scaleOut(Springs.smooth(Spring.StiffnessMedium), targetScale = 0.8f) +
                                    fadeOut(Springs.smooth(Spring.StiffnessMedium)),
                            )
                            .using(SizeTransform(clip = false) { _, _ -> Springs.gentle(Spring.StiffnessMedium) })
                    }
                },
                contentAlignment = Alignment.CenterEnd,
                label = "translateAction",
            ) { translating ->
                val src = remember { MutableInteractionSource() }
                if (translating) {
                    FilledTonalButton(onClick = onStop, interactionSource = src, modifier = Modifier.bounceClick(src)) {
                        Icon(Icons.Filled.Stop, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.translate_stop))
                    }
                } else {
                    Button(
                        onClick = onTranslate,
                        enabled = canTranslate,
                        interactionSource = src,
                        modifier = Modifier.bounceClick(src),
                    ) {
                        Icon(Icons.Filled.Translate, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.translate_action))
                    }
                }
            }
        }
    }
}

/** The picked image in the source area: thumbnail with a clear button, or a spinner while decoding. */
@Composable
private fun SourceImage(
    thumb: ImageBitmap?,
    isPreparing: Boolean,
    clearEnabled: Boolean,
    onClear: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 140.dp)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    ) {
        if (thumb != null) {
            Box {
                Image(
                    bitmap = thumb,
                    contentDescription = stringResource(R.string.translate_image_desc),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .heightIn(max = 220.dp)
                        .clip(MaterialTheme.shapes.large),
                )
                val src = remember { MutableInteractionSource() }
                FilledTonalIconButton(
                    onClick = onClear,
                    enabled = clearEnabled,
                    interactionSource = src,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(32.dp)
                        .bounceClick(src, pressedScale = 0.85f),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.translate_image_remove),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        } else if (isPreparing) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.translate_image_preparing),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The single image-translation entry: a camera icon opening 拍照 / 从相册选择. */
@Composable
private fun ImageSourceButton(enabled: Boolean, onCamera: () -> Unit, onGallery: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        val src = remember { MutableInteractionSource() }
        IconButton(
            onClick = { expanded = true },
            enabled = enabled,
            interactionSource = src,
            modifier = Modifier.bounceClick(src, pressedScale = 0.85f),
        ) {
            Icon(Icons.Outlined.PhotoCamera, contentDescription = stringResource(R.string.translate_image_entry))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.translate_image_camera)) },
                leadingIcon = { Icon(Icons.Outlined.PhotoCamera, contentDescription = null) },
                onClick = {
                    expanded = false
                    onCamera()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.translate_image_gallery)) },
                leadingIcon = { Icon(Icons.Outlined.PhotoLibrary, contentDescription = null) },
                onClick = {
                    expanded = false
                    onGallery()
                },
            )
        }
    }
}

@Composable
private fun OutputCard(
    targetName: String,
    isTranslating: Boolean,
    hasOutput: Boolean,
    imageStage: ImageStage?,
    imageStageSinceMs: Long,
    imageStageBackend: String?,
    output: () -> String,
    stats: com.nekohome.translate.ui.GenStats?,
    showStats: Boolean,
    speaking: Boolean,
    onSpeak: () -> Unit,
) {
    val context = LocalContext.current
    val copiedText = stringResource(R.string.translate_copied)
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val shareTitle = stringResource(R.string.translate_share)
    // While streaming, the card grows line by line; animating each growth restarts a size
    // animation many times a second and keeps clipping the newest line, so size changes snap
    // during a translation and animate only for one-off changes afterwards.
    val sizeSpec = if (isTranslating) snap() else motion(Springs.smooth<IntSize>(Spring.StiffnessMedium))
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(sizeSpec),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 4.dp, bottom = 4.dp)) {
            Text(
                targetName,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(8.dp))
            if (isTranslating && !hasOutput) {
                if (imageStage != null) StageLabel(imageStage, imageStageSinceMs, imageStageBackend)
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(end = 12.dp, top = 8.dp, bottom = 16.dp))
            } else {
                // No SelectionContainer while streaming: selection state would be rebuilt on
                // every update. Text becomes selectable as soon as the translation finishes.
                if (isTranslating) {
                    StreamingText(output, Modifier.padding(end = 12.dp))
                } else {
                    SelectionContainer(Modifier.padding(end = 12.dp)) { StreamingText(output) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(
                    text = if (showStats && stats != null) statsLabel(stats) else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f),
                    modifier = Modifier.weight(1f),
                )
                SpeakButton(
                    speaking = speaking,
                    enabled = hasOutput && !isTranslating,
                    label = stringResource(R.string.tts_speak_output),
                    onClick = onSpeak,
                )
                IconButton(onClick = { fullscreen = true }, enabled = hasOutput && !isTranslating) {
                    Icon(Icons.Outlined.Fullscreen, contentDescription = stringResource(R.string.translate_fullscreen))
                }
                IconButton(
                    onClick = {
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, output())
                        }
                        runCatching { context.startActivity(android.content.Intent.createChooser(send, shareTitle)) }
                    },
                    enabled = hasOutput && !isTranslating,
                ) {
                    Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.translate_share))
                }
                IconButton(
                    onClick = {
                        copyText(context, output())
                        // Android 13+ shows its own clipboard confirmation.
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = hasOutput && !isTranslating,
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.translate_copy))
                }
            }
        }
    }
    if (fullscreen) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { fullscreen = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            targetName,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { fullscreen = false }) { Text(stringResource(R.string.translate_fullscreen_close)) }
                    }
                    SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                        Text(output(), style = MaterialTheme.typography.headlineMedium)
                    }
                }
            }
        }
    }
}

/**
 * Read-aloud toggle: a speaker icon that springs into a stop icon while the text is being read.
 */
@Composable
private fun SpeakButton(speaking: Boolean, enabled: Boolean, label: String, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val reduceMotion = LocalReduceMotion.current
    IconButton(
        onClick = onClick,
        enabled = enabled || speaking,
        interactionSource = src,
        modifier = Modifier.bounceClick(src, pressedScale = 0.85f),
    ) {
        AnimatedContent(
            targetState = speaking,
            transitionSpec = {
                if (reduceMotion) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    (scaleIn(Springs.bouncy(), initialScale = 0.6f) + fadeIn(Springs.smooth(Spring.StiffnessMedium)))
                        .togetherWith(
                            scaleOut(Springs.smooth(Spring.StiffnessMedium), targetScale = 0.6f) +
                                fadeOut(Springs.smooth(Spring.StiffnessMedium)),
                        )
                }
            },
            label = "speak",
        ) { active ->
            if (active) {
                Icon(
                    Icons.Outlined.StopCircle,
                    contentDescription = stringResource(R.string.tts_stop),
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else {
                Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = label)
            }
        }
    }
}

/**
 * Placeholder text while an image is processed: the current stage plus the seconds spent in it,
 * ticking once a second (only this Text recomposes). With GPU acceleration on, [backend] names
 * where the stage runs (「编码图片（GPU）… 3 秒」).
 */
@Composable
private fun StageLabel(stage: ImageStage, sinceMs: Long, backend: String?) {
    val label = when {
        backend != null && stage == ImageStage.ENCODING -> stringResource(R.string.translate_stage_encoding_on, backend)
        backend != null && stage == ImageStage.PREFILL -> stringResource(R.string.translate_stage_prefill_on, backend)
        else -> stringResource(
            when (stage) {
                ImageStage.LOADING_MMPROJ -> R.string.translate_stage_loading_mmproj
                ImageStage.ENCODING -> R.string.translate_stage_encoding
                ImageStage.PREFILL -> R.string.translate_stage_prefill
            },
        )
    }
    var now by remember(stage, sinceMs) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(stage, sinceMs) {
        while (true) {
            delay(1000L - (SystemClock.elapsedRealtime() - sinceMs).mod(1000L))
            now = SystemClock.elapsedRealtime()
        }
    }
    val seconds = ((now - sinceMs) / 1000L).toInt()
    Text(
        text = if (seconds >= 1) stringResource(R.string.translate_stage_elapsed, label, seconds) else label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
        modifier = Modifier.padding(end = 12.dp),
    )
}

/** The only composable that reads the streamed text, so a token update recomposes just this. */
@Composable
private fun StreamingText(text: () -> String, modifier: Modifier = Modifier) {
    Text(
        text(),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier,
    )
}

@Composable
private fun statsLabel(stats: com.nekohome.translate.ui.GenStats): String {
    val base = stringResource(
        R.string.translate_stats,
        stats.generatedTokens,
        String.format(Locale.US, "%.1f", stats.tokensPerSecond),
    )
    val reason = when (stats.stopReason) {
        StopReason.EOG -> null
        StopReason.MAX_TOKENS -> stringResource(R.string.translate_stop_max_tokens)
        StopReason.CONTEXT_FULL -> stringResource(R.string.translate_stop_context_full)
        StopReason.CANCELLED -> stringResource(R.string.translate_stop_cancelled)
        StopReason.ERROR -> stringResource(R.string.translate_stop_error)
    }
    return if (reason == null) base else "$base · $reason"
}

private fun clipboard(context: Context): ClipboardManager? =
    context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

private fun copyText(context: Context, text: String) {
    clipboard(context)?.setPrimaryClip(ClipData.newPlainText("NekoTranslate", text))
}

private fun pasteText(context: Context): String? =
    clipboard(context)?.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(context)
        ?.toString()
        ?.takeIf { it.isNotEmpty() }
