package br.idelmiro.treino;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Mostra o aviso de fim do descanso quando o app não está na tela. */
public class RestReceiver extends BroadcastReceiver {

    static final int NOTIF_ID = 7;

    @Override
    public void onReceive(Context ctx, Intent intent) {
        // Com o app aberto, a própria página toca o aviso.
        if (MainActivity.inForeground) return;

        Intent open = new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = intent.getStringExtra("title");
        String body = intent.getStringExtra("body");
        Notification n = new Notification.Builder(ctx, MainActivity.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(title == null || title.isEmpty() ? "Descanso terminou" : title)
                .setContentText(body == null || body.isEmpty() ? "Hora da próxima série" : body)
                .setCategory(Notification.CATEGORY_ALARM)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        ctx.getSystemService(NotificationManager.class).notify(NOTIF_ID, n);
    }
}
