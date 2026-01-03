package com.example.lifesim;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ScaleGestureDetector;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
        final int W = 256;
        final int H = 256; // 65k клеток
        final int GENOME_SIZE = 64;

        // Данные (Structure of Arrays для скорости и отсутствия GC)
        // 0: dead, 1: alive
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
        // Органику обновляем in-place (атомарно или с допущениями), чтобы экономить память

        // Графика
        Bitmap bitmap;
        int[] pixels = new int[W * H];
        Matrix matrix = new Matrix();
        
        // Управление
        ScaleGestureDetector scaleDetector;
        float scaleFactor = 4.0f;
        float camX = 0, camY = 0;
        float lastTouchX, lastTouchY;
        int activePointerId = -1;

        // Статистика
        long lastTime = System.nanoTime();
        int frames = 0;
        int fps = 0;
        int liveCount = 0;

        public SimulationView(android.content.Context context) {
            super(context);
            holder = getHolder();
            bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            initWorld();

            scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                    scaleFactor *= detector.getScaleFactor();
                    scaleFactor = Math.max(1.0f, Math.min(scaleFactor, 20.0f));
                    return true;
                }
            });
        }

        private void initWorld() {
            Random rng = new Random();
            for (int i = 0; i < W * H; i++) {
                organic[i] = rng.nextInt(50);
                if (rng.nextInt(100) < 20) { // 20% заполнения
                    botAlive[i] = 1;
                    botEnergy[i] = 500;
                    botDir[i] = (byte) rng.nextInt(8);
                    for (int g = 0; g < GENOME_SIZE; g++) {
                        botGenome[i * GENOME_SIZE + g] = (byte) rng.nextInt(64);
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

        // --- ЛОГИКА (ПАРАЛЛЕЛЬНАЯ) ---
        private void update() {
            // Очистка следующего буфера (только флаги жизни)
            Arrays.fill(nextAlive, (byte)0);
            
            // Parallel Stream в Java 8+ автоматически использует все ядра CPU
            // Это аналог #pragma omp parallel for
            IntStream.range(0, W * H).parallel().forEach(i -> {
                if (botAlive[i] == 1) {
                    processBot(i);
                } else {
                    // Разложение органики или случайный рост
                    if (Math.random() > 0.999) organic[i] = Math.min(organic[i] + 10, 255);
                }
            });

            // Swap buffers (ссылки)
            byte[] tmpAlive = botAlive; botAlive = nextAlive; nextAlive = tmpAlive;
            int[] tmpEnergy = botEnergy; botEnergy = nextEnergy; nextEnergy = tmpEnergy;
            byte[] tmpDir = botDir; botDir = nextDir; nextDir = tmpDir;
            byte[] tmpIp = botIp; botIp = nextIp; nextIp = tmpIp;
            byte[] tmpGenome = botGenome; botGenome = nextGenome; nextGenome = tmpGenome;
            
            // Подсчет живых
            // (Можно оптимизировать, но для примера сойдет)
            liveCount = 0; // Считается в Draw для скорости
        }

        private void processBot(int idx) {
            // Если энергии мало - умираем (становимся органикой)
            if (botEnergy[idx] <= 0) {
                organic[idx] = Math.min(organic[idx] + 20, 255);
                return; // В nextAlive по умолчанию 0
            }

            // Копируем состояние
            nextAlive[idx] = 1;
            nextEnergy[idx] = botEnergy[idx];
            nextDir[idx] = botDir[idx];
            nextIp[idx] = botIp[idx];
            System.arraycopy(botGenome, idx * GENOME_SIZE, nextGenome, idx * GENOME_SIZE, GENOME_SIZE);

            // Виртуальная машина
            int steps = 0;
            boolean turnEnded = false;
            int ip = nextIp[idx] & 0xFF; // unsigned conversion
            int genomeBase = idx * GENOME_SIZE;

            while (steps < 10 && !turnEnded) {
                int cmd = nextGenome[genomeBase + ip] & 0xFF;
                ip = (ip + 1) % GENOME_SIZE;

                if (cmd < 8) { // Move IP
                    ip = (ip + cmd) % GENOME_SIZE;
                } else if (cmd < 16) { // Rotate
                    nextDir[idx] = (byte) ((nextDir[idx] + (cmd - 8)) % 8);
                } else if (cmd == 20) { // Photosynthesis
                    nextEnergy[idx] += 5;
                    turnEnded = true;
                } else if (cmd == 40) { // Move
                    int dir = nextDir[idx];
                    int dx = (dir == 1 || dir == 2 || dir == 3) ? 1 : ((dir == 5 || dir == 6 || dir == 7) ? -1 : 0);
                    int dy = (dir == 0 || dir == 1 || dir == 7) ? -1 : ((dir == 3 || dir == 4 || dir == 5) ? 1 : 0);
                    
                    int nx = (idx % W + dx + W) % W;
                    int ny = (idx / W + dy + H) % H;
                    int nIdx = ny * W + nx;

                    // Простейшая коллизия: если там никого нет В ТЕКУЩЕМ кадре
                    if (botAlive[nIdx] == 0) {
                        // Перемещаем в новый слот
                        nextAlive[nIdx] = 1;
                        nextEnergy[nIdx] = nextEnergy[idx] - 2;
                        nextDir[nIdx] = nextDir[idx];
                        nextIp[nIdx] = (byte) ip;
                        System.arraycopy(nextGenome, idx * GENOME_SIZE, nextGenome, nIdx * GENOME_SIZE, GENOME_SIZE);
                        
                        nextAlive[idx] = 0; // Освобождаем старый
                    }
                    turnEnded = true;
                }
                steps++;
            }
            
            // Сохраняем IP если не переехали
            if (nextAlive[idx] == 1) {
                nextIp[idx] = (byte) ip;
                nextEnergy[idx] -= 1;
            }
        }

        // --- ОТРИСОВКА ---
        private void draw() {
            if (!holder.getSurface().isValid()) return;

            // 1. Заполняем массив пикселей (Software Rendering)
            int count = 0;
            for (int i = 0; i < W * H; i++) {
                if (botAlive[i] == 1) {
                    pixels[i] = 0xFF00FF00; // Green bot
                    count++;
                } else {
                    int val = Math.min(organic[i] * 3, 255);
                    pixels[i] = 0xFF000000 | (val << 16) | (val << 8) | 10; // Brownish
                }
            }
            liveCount = count;

            // 2. Загружаем в Bitmap
            bitmap.setPixels(pixels, 0, W, 0, 0, W, H);

            // 3. Рисуем на экране с зумом
            Canvas canvas = holder.lockCanvas();
            if (canvas != null) {
                canvas.drawColor(Color.BLACK);
                
                matrix.reset();
                matrix.postTranslate(-W/2f, -H/2f); // Center
                matrix.postScale(scaleFactor, scaleFactor);
                matrix.postTranslate(W/2f + camX, H/2f + camY); // Pan
                matrix.postTranslate(canvas.getWidth()/2f - W/2f, canvas.getHeight()/2f - H/2f); // Screen center

                Paint p = new Paint();
                p.setFilterBitmap(false); // Пиксель-арт стиль (без сглаживания)
                canvas.drawBitmap(bitmap, matrix, p);

                // UI
                p.setColor(Color.WHITE);
                p.setTextSize(40);
                canvas.drawText("FPS: " + fps, 50, 50, p);
                canvas.drawText("Bots: " + liveCount, 50, 100, p);

                holder.unlockCanvasAndPost(canvas);
            }
        }

        // --- УПРАВЛЕНИЕ ---
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            scaleDetector.onTouchEvent(event);
            
            final int action = event.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN: {
                    final int pointerIndex = event.getActionIndex();
                    final float x = event.getX(pointerIndex);
                    final float y = event.getY(pointerIndex);
                    lastTouchX = x;
                    lastTouchY = y;
                    activePointerId = event.getPointerId(0);
                    break;
                }
                case MotionEvent.ACTION_MOVE: {
                    final int pointerIndex = event.findPointerIndex(activePointerId);
                    if (pointerIndex == -1) break;
                    
                    final float x = event.getX(pointerIndex);
                    final float y = event.getY(pointerIndex);
                    
                    if (!scaleDetector.isInProgress()) {
                        camX += (x - lastTouchX) / scaleFactor; // Pan slow when zoomed in
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
