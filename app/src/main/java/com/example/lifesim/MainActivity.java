
package com.example.lifesim;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.Arrays;
import java.util.Random;
import java.util.stream.IntStream;

public class MainActivity extends Activity {

    private SimulationView simulationView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        simulationView = new SimulationView(this);
        setContentView(simulationView);
    }

    @Override
    protected void onPause() {
        super.onPause();
        simulationView.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        simulationView.resume();
    }

    // --- ENGINE & VIEW ---
    class SimulationView extends SurfaceView implements Runnable {
        Thread thread = null;
        volatile boolean isRunning = false;
        SurfaceHolder holder;

        // Мир
        final int W = 128; // Уменьшил размер для более крупного отображения деталей
        final int H = 128;
        final int GENOME_SIZE = 64;

        // Данные (Structure of Arrays)
        byte[] botAlive = new byte[W * H];
        int[] botEnergy = new int[W * H];
        byte[] botDir = new byte[W * H];
        byte[] botIp = new byte[W * H];
        byte[] botGenome = new byte[W * H * GENOME_SIZE];
        int[] organic = new int[W * H];

        // Буферы для следующего шага
        byte[] nextAlive = new byte[W * H];
        int[] nextEnergy = new int[W * H];
        byte[] nextDir = new byte[W * H];
        byte[] nextIp = new byte[W * H];
        byte[] nextGenome = new byte[W * H * GENOME_SIZE];

        // Графика и Управление
        Matrix matrix = new Matrix();
        ScaleGestureDetector scaleDetector;
        float scaleFactor = 8.0f; // Начальный зум побольше
        float camX = 0, camY = 0;
        float lastTouchX, lastTouchY;
        int activePointerId = -1;

        // Статистика
        long lastTime = System.nanoTime();
        int frames = 0;
        int fps = 0;
        int liveCount = 0;

        // Кисти для рисования
        Paint organicPaint, botBodyPaint, connectionPaint, appendagePaint, textPaint;

        public SimulationView(android.content.Context context) {
            super(context);
            holder = getHolder();
            initWorld();
            initPaints();

            scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                    scaleFactor *= detector.getScaleFactor();
                    scaleFactor = Math.max(2.0f, Math.min(scaleFactor, 50.0f));
                    return true;
                }
            });
        }

        private void initPaints() {
            organicPaint = new Paint();
            organicPaint.setColor(Color.LTGRAY);
            organicPaint.setStyle(Paint.Style.FILL);
            organicPaint.setAntiAlias(true);

            botBodyPaint = new Paint();
            botBodyPaint.setColor(0xFFFFA500); // Оранжевый
            botBodyPaint.setStyle(Paint.Style.FILL);
            botBodyPaint.setAntiAlias(true);

            connectionPaint = new Paint();
            connectionPaint.setColor(Color.BLACK);
            connectionPaint.setStrokeWidth(0.1f);
            connectionPaint.setAntiAlias(true);

            appendagePaint = new Paint();
            appendagePaint.setStyle(Paint.Style.FILL);
            appendagePaint.setAntiAlias(true);

            textPaint = new Paint();
            textPaint.setColor(Color.BLACK); // Черный текст на белом фоне
            textPaint.setTextSize(40);
        }

        private void initWorld() {
            Random rng = new Random();
            for (int i = 0; i < W * H; i++) {
                organic[i] = rng.nextInt(100);
                if (rng.nextInt(100) < 10) { // 10% заполнения ботами
                    botAlive[i] = 1;
                    botEnergy[i] = 500;
                    botDir[i] = (byte) rng.nextInt(8);
                    for (int g = 0; g < GENOME_SIZE; g++) {
                        // Больше шансов на гены фотосинтеза (20) и движения (40) для наглядности
                        int r = rng.nextInt(100);
                        if (r < 20) botGenome[i * GENOME_SIZE + g] = 20;
                        else if (r < 40) botGenome[i * GENOME_SIZE + g] = 40;
                        else botGenome[i * GENOME_SIZE + g] = (byte) rng.nextInt(64);
                    }
                }
            }
        }

        public void pause() {
            isRunning = false;
            try { thread.join(); } catch (InterruptedException e) {}
        }

        public void resume() {
            isRunning = true;
            thread = new Thread(this);
            thread.start();
        }

        @Override
        public void run() {
            while (isRunning) {
                update();
                draw();
                calculateFPS();
            }
        }

        // --- ЛОГИКА (Без изменений) ---
        private void update() {
            Arrays.fill(nextAlive, (byte)0);
            IntStream.range(0, W * H).parallel().forEach(i -> {
                if (botAlive[i] == 1) {
                    processBot(i);
                } else {
                    if (Math.random() > 0.995) organic[i] = Math.min(organic[i] + 20, 255);
                }
            });
            byte[] tmpAlive = botAlive; botAlive = nextAlive; nextAlive = tmpAlive;
            int[] tmpEnergy = botEnergy; botEnergy = nextEnergy; nextEnergy = tmpEnergy;
            byte[] tmpDir = botDir; botDir = nextDir; nextDir = tmpDir;
            byte[] tmpIp = botIp; botIp = nextIp; nextIp = tmpIp;
            byte[] tmpGenome = botGenome; botGenome = nextGenome; nextGenome = tmpGenome;
        }

        private void processBot(int idx) {
            if (botEnergy[idx] <= 0) {
                organic[idx] = Math.min(organic[idx] + 50, 255);
                return;
            }
            nextAlive[idx] = 1;
            nextEnergy[idx] = botEnergy[idx];
            nextDir[idx] = botDir[idx];
            nextIp[idx] = botIp[idx];
            System.arraycopy(botGenome, idx * GENOME_SIZE, nextGenome, idx * GENOME_SIZE, GENOME_SIZE);

            int steps = 0;
            boolean turnEnded = false;
            int ip = nextIp[idx] & 0xFF;
            int genomeBase = idx * GENOME_SIZE;

            while (steps < 10 && !turnEnded) {
                int cmd = nextGenome[genomeBase + ip] & 0xFF;
                ip = (ip + 1) % GENOME_SIZE;
                if (cmd < 8) { ip = (ip + cmd) % GENOME_SIZE; }
                else if (cmd < 16) { nextDir[idx] = (byte) ((nextDir[idx] + (cmd - 8)) % 8); }
                else if (cmd == 20) { nextEnergy[idx] += 10; turnEnded = true; }
                else if (cmd == 40) {
                    int dir = nextDir[idx];
                    int dx = (dir == 1 || dir == 2 || dir == 3) ? 1 : ((dir == 5 || dir == 6 || dir == 7) ? -1 : 0);
                    int dy = (dir == 0 || dir == 1 || dir == 7) ? -1 : ((dir == 3 || dir == 4 || dir == 5) ? 1 : 0);
                    int nx = (idx % W + dx + W) % W;
                    int ny = (idx / W + dy + H) % H;
                    int nIdx = ny * W + nx;
                    if (botAlive[nIdx] == 0 && nextAlive[nIdx] == 0) {
                        nextAlive[nIdx] = 1;
                        nextEnergy[nIdx] = nextEnergy[idx] - 5;
                        nextDir[nIdx] = nextDir[idx];
                        nextIp[nIdx] = (byte) ip;
                        System.arraycopy(nextGenome, idx * GENOME_SIZE, nextGenome, nIdx * GENOME_SIZE, GENOME_SIZE);
                        nextAlive[idx] = 0;
                    }
                    turnEnded = true;
                }
                steps++;
            }
            if (nextAlive[idx] == 1) {
                nextIp[idx] = (byte) ip;
                nextEnergy[idx] -= 2;
            }
        }

        // --- НОВАЯ ОТРИСОВКА ---
        private void draw() {
            if (!holder.getSurface().isValid()) return;

            Canvas canvas = holder.lockCanvas();
            if (canvas != null) {
                // Белый фон
                canvas.drawColor(Color.WHITE);

                // Применяем камеру (зум и панорамирование)
                matrix.reset();
                matrix.postTranslate(-W/2f, -H/2f);
                matrix.postScale(scaleFactor, scaleFactor);
                matrix.postTranslate(W/2f + camX, H/2f + camY);
                matrix.postTranslate(canvas.getWidth()/2f - W/2f, canvas.getHeight()/2f - H/2f);
                canvas.setMatrix(matrix);

                int count = 0;
                for (int i = 0; i < W * H; i++) {
                    float x = i % W + 0.5f;
                    float y = i / W + 0.5f;

                    // 1. Рисуем органику (серые круги)
                    if (organic[i] > 10) {
                        float radius = Math.min(organic[i] / 600f, 0.3f);
                        canvas.drawCircle(x, y, radius, organicPaint);
                    }

                    // 2. Рисуем ботов
                    if (botAlive[i] == 1) {
                        count++;
                        // Тело бота (оранжевый квадрат)
                        canvas.drawRect(x - 0.3f, y - 0.3f, x + 0.3f, y + 0.3f, botBodyPaint);

                        // Придатки на основе генома
                        int genomeBase = i * GENOME_SIZE;
                        for (int g = 0; g < GENOME_SIZE; g += 4) { // Рисуем не все гены, чтобы не было каши
                            int gene = botGenome[genomeBase + g] & 0xFF;
                            if (gene == 20 || gene == 40) {
                                int dir = g % 8;
                                float dx = (dir == 1 || dir == 2 || dir == 3) ? 1 : ((dir == 5 || dir == 6 || dir == 7) ? -1 : 0);
                                float dy = (dir == 0 || dir == 1 || dir == 7) ? -1 : ((dir == 3 || dir == 4 || dir == 5) ? 1 : 0);
                                
                                float endX = x + dx * 0.7f;
                                float endY = y + dy * 0.7f;

                                // Линия связи
                                canvas.drawLine(x, y, endX, endY, connectionPaint);

                                // Цвет придатка
                                if (gene == 20) appendagePaint.setColor(Color.GREEN); // Фотосинтез
                                else appendagePaint.setColor(Color.BLUE); // Движение

                                // Сам придаток (круг)
                                canvas.drawCircle(endX, endY, 0.2f, appendagePaint);
                            }
                        }
                    }
                }
                liveCount = count;

                // Сбрасываем матрицу для рисования UI поверх всего
                canvas.setMatrix(null);
                canvas.drawText("FPS: " + fps, 50, 60, textPaint);
                canvas.drawText("Bots: " + liveCount, 50, 120, textPaint);

                holder.unlockCanvasAndPost(canvas);
            }
        }

        // --- УПРАВЛЕНИЕ (Без изменений) ---
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            scaleDetector.onTouchEvent(event);
            final int action = event.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN: {
                    final int pointerIndex = event.getActionIndex();
                    lastTouchX = event.getX(pointerIndex);
                    lastTouchY = event.getY(pointerIndex);
                    activePointerId = event.getPointerId(0);
                    break;
                }
                case MotionEvent.ACTION_MOVE: {
                    final int pointerIndex = event.findPointerIndex(activePointerId);
                    if (pointerIndex == -1) break;
                    final float x = event.getX(pointerIndex);
                    final float y = event.getY(pointerIndex);
                    if (!scaleDetector.isInProgress()) {
                        camX += (x - lastTouchX) / scaleFactor;
                        camY += (y - lastTouchY) / scaleFactor;
                    }
                    lastTouchX = x;
                    lastTouchY = y;
                    break;
                }
            }
            return true;
        }

        private void calculateFPS() {
            frames++;
            if (System.nanoTime() - lastTime >= 1000000000) {
                fps = frames;
                frames = 0;
                lastTime = System.nanoTime();
            }
        }
    }
}
