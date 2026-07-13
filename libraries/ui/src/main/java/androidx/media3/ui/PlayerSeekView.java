/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package androidx.media3.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import java.util.Formatter;
import java.util.Locale;

/** A compact seek bar with position and duration labels for custom player controls. */
@UnstableApi
public final class PlayerSeekView extends FrameLayout
    implements Player.Listener, TimeBar.OnScrubListener {

  private final TextView positionView;
  private final TextView durationView;
  private final DefaultTimeBar timeBar;
  private final StringBuilder formatBuilder;
  private final Formatter formatter;
  private final Runnable progressUpdater;
  @Nullable private Player player;
  private boolean scrubbing;

  public PlayerSeekView(Context context) {
    this(context, null);
  }

  public PlayerSeekView(Context context, @Nullable AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public PlayerSeekView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    LayoutInflater.from(context).inflate(R.layout.exo_player_seek_view, this);
    positionView = findViewById(R.id.exo_position);
    durationView = findViewById(R.id.exo_duration);
    timeBar = findViewById(R.id.exo_progress);
    formatBuilder = new StringBuilder();
    formatter = new Formatter(formatBuilder, Locale.getDefault());
    progressUpdater = this::updateProgress;
    timeBar.addListener(this);
    updateProgress();
  }

  /** Sets the player whose position is shown, or {@code null} to detach. */
  public void setPlayer(@Nullable Player player) {
    if (this.player == player) {
      return;
    }
    if (this.player != null) {
      this.player.removeListener(this);
    }
    this.player = player;
    if (player != null) {
      player.addListener(this);
    }
    updateProgress();
  }

  /** Returns the time bar so callers can customize keyboard increments and scrub listeners. */
  public TimeBar getTimeBar() {
    return timeBar;
  }

  @Override
  public void onEvents(Player player, Player.Events events) {
    updateProgress();
  }

  @Override
  public void onScrubStart(TimeBar timeBar, long position) {
    scrubbing = true;
    positionView.setText(formatTime(position));
  }

  @Override
  public void onScrubMove(TimeBar timeBar, long position) {
    positionView.setText(formatTime(position));
  }

  @Override
  public void onScrubStop(TimeBar timeBar, long position, boolean canceled) {
    scrubbing = false;
    if (!canceled && player != null && player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
      player.seekTo(position);
    }
    updateProgress();
  }

  @Override
  protected void onDetachedFromWindow() {
    removeCallbacks(progressUpdater);
    super.onDetachedFromWindow();
  }

  private void updateProgress() {
    removeCallbacks(progressUpdater);
    long position = 0;
    long bufferedPosition = 0;
    long duration = 0;
    if (player != null) {
      position = Math.max(0, player.getCurrentPosition());
      bufferedPosition = Math.max(0, player.getBufferedPosition());
      duration = player.getDuration() == C.TIME_UNSET ? 0 : Math.max(0, player.getDuration());
    }
    if (!scrubbing) {
      positionView.setText(formatTime(position));
    }
    durationView.setText(formatTime(duration));
    timeBar.setDuration(duration);
    timeBar.setPosition(position);
    timeBar.setBufferedPosition(bufferedPosition);
    timeBar.setEnabled(player != null && duration > 0);
    if (isAttachedToWindow() && player != null) {
      postDelayed(progressUpdater, player.isPlaying() ? 250 : 1000);
    }
  }

  private String formatTime(long timeMs) {
    return Util.getStringForTime(formatBuilder, formatter, timeMs);
  }
}
