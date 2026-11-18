# Food Estimator (Android, Stage 1)

Android app that estimates weight, kcal and macros (protein, fat, carbs) of a meal from
one or more photos, entirely on the phone. Model training lives in the sibling repository
`OnDeviceFoodRegEst` (Stage 1 results: `docs/stage1_results.md` there).

## What it does

1. Take 1–5 photos of one meal with the camera (CameraX), or pick them from the gallery.
2. Each photo runs through the Stage 1 model (MobileNetV3-Large, 320 px, FP16, centre crop by default, LiteRT
   `CompiledModel` on the GPU, CPU fallback). Its weight estimate shows up on the thumbnail.
3. **Estimate** combines the photos by the median of the per-photo predictions (weight and
   composition per 100 g); macros and kcal follow from both, so they are always consistent.
4. Result: kcal, weight with the calibrated 90 % range, protein / fat / carbs (total and
   per 100 g), the most likely food classes and ingredients, latency per photo.
5. Correction: type the real weight (macros and kcal are rescaled with the predicted
   composition) and pick the right food class from the suggestions.

Nothing is stored or sent anywhere yet.

### Settings tab

- **Developer info**: model and accelerator in the status line, ms per photo on the
  thumbnails, per-photo timings and raw outputs (log mass, calibrated log-std, 90 % factor),
  median model / preprocessing time, load + warm-up time and Java / native heap on the result.
- **Live estimate on camera**: runs the model on every preview frame (CameraX image
  analysis, keep-only-latest) and shows weight, kcal, ms per frame, model ms and fps.
- **Image preprocessing (crop)**: *Center crop* (square from the middle, edges cut off),
  *Pad to square* (whole photo, grey bars) or *Both (compare)*: both models run on every
  photo; thumbnails, live overlay, benchmark and a "Crop comparison" card on the result
  show both estimates. Each mode uses the model trained with that preprocessing.
- **Accelerator**: Auto (GPU, CPU fallback), GPU only or CPU only; the models reload.
- **Benchmark**: 50 runs on the last photo (or a grey image): median, mean, p90, fastest.
- **Model**: file, size, input, precision, load time, memory, app version.

Settings are kept in SharedPreferences.

## Build and install

Requires Android Studio's JDK and the Android SDK (platform 37); Gradle and AGP versions
match `FoodDatasetApp`.

```bash
export JAVA_HOME=/usr/local/android-studio/jbr
./gradlew :app:testDebugUnitTest     # JVM unit tests (estimate maths, model metadata)
./gradlew :app:installDebug          # phone connected with USB debugging enabled
```

`minSdk` is 29 (Android 10); the Pixel 8 Pro is the reference device.

## Model

`app/src/main/assets/` holds two models (MobileNetV3-Large, 320 px, FP16, trained on
cleaned labels) and their contracts:

| Files | Preprocessing | Test score (desktop check) |
|---|---|---|
| `food_stage1_center_fp16.tflite` + `food_stage1_center.json` | shorter side to 320, centre crop | 24.65 |
| `food_stage1_pad_fp16.tflite` + `food_stage1_pad.json` | pad to square with RGB (124, 116, 104), resize to 320 × 320 | 24.26 |

Input float32 `[1, 320, 320, 3]` RGB in 0..1 (ImageNet normalisation is inside the model).
Output one vector `[mass_g, kcal, fat_g, carb_g, protein_g, log_mass, log_std90, 50 dish probs,
171 ingredient probs]`. The JSON holds labels, input size, crop mode, output layout and calibration.

To update them, export from the training repository and copy the files:

```bash
cd ../OnDeviceFoodRegEst
E=~/.cache/ondevice_food/export-venv/bin/python
PYTHONPATH=. $E -m training.export_litert runs/clean/mobilenetv3_large_img320_s0/best.pt --out export/food_stage1_center
PYTHONPATH=. $E -m training.export_litert runs/clean/mobilenetv3_large_img320_pad_s0/best.pt --out export/food_stage1_pad
cp export/food_stage1_{center,pad}.json export/food_stage1_{center,pad}_fp16.tflite ../OnDeviceFoodRegEstApp/app/src/main/assets/
```

Checked on the desktop with `training.eval_litert` (`--crop pad` for the pad model): both FP16
files score the same as their PyTorch checkpoints on the cleaned test sets (side views 60 %).

## Code

- `ml/FoodEstimator.kt`: loads the model (GPU → CPU fallback, warm-up), runs one photo.
- `ml/Preprocess.kt`: decoding with EXIF rotation, resize + centre crop, tensor.
- `ml/MealEstimate.kt`: per-photo output, median over photos, weight correction.
- `ui/`: capture, result and settings screens; `MainViewModel`.
- `settings/Settings.kt`: persisted settings, accelerator choice.

## Not done yet

- Not yet run on a real phone: GPU delegate, latency, memory and heat still to measure.
- Saving meals / own test-set logging, INT8 model, depth (Stage 2).
