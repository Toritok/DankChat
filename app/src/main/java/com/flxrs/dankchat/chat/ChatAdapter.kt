package com.flxrs.dankchat.chat

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ImageSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.text.bold
import androidx.core.text.color
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.flxrs.dankchat.R
import com.flxrs.dankchat.databinding.ChatItemBinding
import com.flxrs.dankchat.preferences.DankChatPreferenceStore
import com.flxrs.dankchat.service.twitch.emote.EmoteManager
import com.flxrs.dankchat.utils.DrawableTarget
import com.flxrs.dankchat.utils.EmoteDrawableTarget
import com.flxrs.dankchat.utils.GifDrawableTarget
import com.flxrs.dankchat.utils.normalizeColor
import com.linkedin.urls.detection.UrlDetector
import com.linkedin.urls.detection.UrlDetectorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class ChatAdapter(
    private val onListChanged: (position: Int) -> Unit,
    private val onUserClicked: (user: String) -> Unit,
    private val onMessageLongClick: (message: String) -> Unit
) : ListAdapter<ChatItem, ChatAdapter.ViewHolder>(DetectDiff()) {
    inner class ViewHolder(val binding: ChatItemBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(ChatItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onCurrentListChanged(previousList: MutableList<ChatItem>, currentList: MutableList<ChatItem>) {
        onListChanged(currentList.size - 1)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        val view = holder.binding.itemText
        EmoteManager.gifCallback.removeView(view)
        holder.binding.executePendingBindings()
        Glide.with(view).clear(view)

        super.onViewRecycled(holder)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: ViewHolder, position: Int): Unit = with(holder.binding.itemText) {
        isClickable = false
        text = ""
        movementMethod = LinkMovementMethod.getInstance()
        EmoteManager.gifCallback.addView(this)

        getItem(position).message.apply {
            var ignoreClicks = false
            if (!this.isSystem) this@with.setOnLongClickListener {
                ignoreClicks = true
                onMessageLongClick(this.message)
                true
            }

            this@with.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    CoroutineScope(Dispatchers.Default).launch {
                        delay(200)
                        ignoreClicks = false
                    }
                }
                false
            }

            val lineHeight = this@with.lineHeight
            val scaleFactor = lineHeight * 1.5 / 112
            val currentUserName = DankChatPreferenceStore(this@with.context).getUserName() ?: ""

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val foregroundColor =
                    if (timedOut) ContextCompat.getColor(this@with.context, R.color.colorTimeOut) else Color.TRANSPARENT
                foreground = ColorDrawable(foregroundColor)
            }

            val background = if (isNotify) R.color.sub_background
            else if (currentUserName.isNotBlank() && !name.equals(currentUserName, true)
                && !timedOut && !isSystem && message.contains(currentUserName, true)
            ) R.color.highlight_background
            else android.R.color.transparent
            this@with.setBackgroundResource(background)


            val displayName = if (isAction) "$name " else if (name.isBlank()) "" else "$name: "
            var badgesLength = 0
            val timestampPreferenceKey = this@with.context.getString(R.string.preference_timestamp_key)
            val preferences = PreferenceManager.getDefaultSharedPreferences(this@with.context)
            val (prefixLength, spannable) = if (preferences.getBoolean(timestampPreferenceKey, true)) {
                time.length + 1 + displayName.length to SpannableStringBuilder().bold { append("$time ") }
            } else {
                displayName.length to SpannableStringBuilder()
            }

            badges.forEach { badge ->
                spannable.append("  ")
                val start = spannable.length - 2
                val end = spannable.length - 1
                badgesLength += 2
                Glide.with(this@with)
                    .asDrawable()
                    .load(badge.url)
                    .placeholder(R.drawable.ic_missing_emote)
                    .error(R.drawable.ic_missing_emote)
                    .into(DrawableTarget {
                        val width = (lineHeight * it.intrinsicWidth / it.intrinsicHeight.toFloat()).roundToInt()
                        it.setBounds(0, 0, width, lineHeight)
                        val imageSpan = ImageSpan(it, ImageSpan.ALIGN_BOTTOM)
                        spannable.setSpan(imageSpan, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        text = spannable
                    })
            }

            val normalizedColor = normalizeColor(color)
            spannable.bold { color(normalizedColor) { append(displayName) } }

            if (isAction) {
                spannable.color(normalizedColor) { append(message) }
            } else {
                spannable.append(message)
            }

            //clicking usernames
            if (name.isNotBlank()) {
                val userClickableSpan = object : ClickableSpan() {
                    override fun updateDrawState(ds: TextPaint) {
                        ds.isUnderlineText = false
                        ds.color = normalizedColor
                    }

                    override fun onClick(v: View) {
                        if (!ignoreClicks) onUserClicked(name)
                    }
                }
                spannable.setSpan(
                    userClickableSpan,
                    prefixLength - displayName.length + badgesLength,
                    prefixLength + badgesLength,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            //links
            UrlDetector(message, UrlDetectorOptions.Default).detect().forEach { url ->
                val clickableSpan = object : ClickableSpan() {
                    override fun onClick(v: View) {
                        try {
                            if (!ignoreClicks)
                                androidx.browser.customtabs.CustomTabsIntent.Builder()
                                    .addDefaultShareMenuItem()
                                    .setToolbarColor(ContextCompat.getColor(v.context, R.color.colorPrimary))
                                    .setShowTitle(true)
                                    .build().launchUrl(v.context, Uri.parse(url.fullUrl))
                        } catch (e: ActivityNotFoundException) {
                            Log.e("ViewBinding", Log.getStackTraceString(e))
                        }

                    }
                }
                val start = prefixLength + badgesLength + message.indexOf(url.originalUrl)
                val end = start + url.originalUrl.length
                spannable.setSpan(clickableSpan, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                text = spannable
            }

            emotes.forEach { e ->
                e.positions.forEach { pos ->
                    val split = pos.split('-')
                    val start = split[0].toInt() + prefixLength + badgesLength
                    val end = split[1].toInt() + prefixLength + badgesLength
                    if (e.isGif) {
                        val gifDrawable = EmoteManager.gifCache[e.keyword]
                        if (gifDrawable != null) {
                            val height = (gifDrawable.intrinsicHeight * scaleFactor).roundToInt()
                            val width = (gifDrawable.intrinsicWidth * scaleFactor).roundToInt()
                            gifDrawable.setBounds(0, 0, width, height)

                            val imageSpan = ImageSpan(gifDrawable, ImageSpan.ALIGN_BOTTOM)
                            spannable.setSpan(imageSpan, start, end, Spannable.SPAN_EXCLUSIVE_INCLUSIVE)
                            text = spannable
                        } else Glide.with(this@with)
                            .`as`(ByteArray::class.java)
                            .load(e.url)
                            .placeholder(R.drawable.ic_missing_emote)
                            .error(R.drawable.ic_missing_emote)
                            .into(GifDrawableTarget(e.keyword, true) {
                                val height = (it.intrinsicHeight * scaleFactor).roundToInt()
                                val width = (it.intrinsicWidth * scaleFactor).roundToInt()
                                it.setBounds(0, 0, width, height)

                                val imageSpan = ImageSpan(it, ImageSpan.ALIGN_BOTTOM)
                                spannable.setSpan(imageSpan, start, end, Spannable.SPAN_EXCLUSIVE_INCLUSIVE)
                                text = spannable
                            })
                    } else Glide.with(this@with)
                        .asBitmap()
                        .load(e.url)
                        .placeholder(R.drawable.ic_missing_emote)
                        .error(R.drawable.ic_missing_emote)
                        .into(EmoteDrawableTarget(e, context) {
                            val ratio = it.intrinsicWidth / it.intrinsicHeight.toFloat()
                            val height = when {
                                it.intrinsicHeight < 55 && e.keyword.isBlank() -> (70 * scaleFactor).roundToInt()
                                it.intrinsicHeight in 55..111 && e.keyword.isBlank() -> (112 * scaleFactor).roundToInt()
                                else -> (it.intrinsicHeight * scaleFactor).roundToInt()
                            }
                            val width = (height * ratio).roundToInt()
                            it.setBounds(0, 0, width, height)

                            val imageSpan = ImageSpan(it, ImageSpan.ALIGN_BOTTOM)
                            spannable.setSpan(imageSpan, start, end, Spannable.SPAN_EXCLUSIVE_INCLUSIVE)
                            text = spannable
                        })
                }
            }
            text = spannable
        }
    }

    private class DetectDiff : DiffUtil.ItemCallback<ChatItem>() {
        override fun areItemsTheSame(oldItem: ChatItem, newItem: ChatItem): Boolean {
            return oldItem == newItem
        }

        override fun areContentsTheSame(oldItem: ChatItem, newItem: ChatItem): Boolean {
            return oldItem.message == newItem.message
        }
    }
}