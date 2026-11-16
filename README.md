# Food Estimator (Android, Stage 1)

Android app that estimates weight, kcal and macros (protein, fat, carbs) of a meal from
one or more photos, entirely on the phone. Model training lives in the sibling repository
`OnDeviceFoodRegEst` (Stage 1 results: `docs/stage1_results.md` there).

## What it does

1. Take 1–5 photos of one meal with the camera (CameraX), or pick them from the gallery.
2. Each photo runs through the Stage 1 model (MobileNetV3-Large, 320 px, FP16, LiteRT
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
- **Accelerator**: Auto (GPU, CPU fallback), GPU only or CPU only; the model reloads.
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

`app/src/main/assets/` holds the model and its contract:

- `food_stage1_fp16.tflite`: input float32 `[1, 320, 320, 3]` RGB in 0..1 (shorter side
  resized to 320, centre crop; ImageNet normalisation is inside the model). Output one vector
  `[mass_g, kcal, fat_g, carb_g, protein_g, log_mass, log_std90, 50 dish probs, 171 ingredient probs]`.
- `food_stage1.json`: labels, input size, output layout, calibration.

To update it, export from the training repository and copy both files:

```bash
cd ../OnDeviceFoodRegEst
PYTHONPATH=. ~/.cache/ondevice_food/export-venv/bin/python -m training.export_litert \
    runs/final_hires/mobilenetv3_large_img320_s0/best.pt --out export/food_stage1
cp export/food_stage1_fp16.tflite export/food_stage1.json ../OnDeviceFoodRegEstApp/app/src/main/assets/
```

Checked on the desktop with `training.eval_litert`: the FP16 file scores 29.22 on the
test sets vs. 29.21 for the PyTorch checkpoint (same metric, side views 60 %).

## Code

- `ml/FoodEstimator.kt`: loads the model (GPU → CPU fallback, warm-up), runs one photo.
- `ml/Preprocess.kt`: decoding with EXIF rotation, resize + centre crop, tensor.
- `ml/MealEstimate.kt`: per-photo output, median over photos, weight correction.
- `ui/`: capture, result and settings screens; `MainViewModel`.
- `settings/Settings.kt`: persisted settings, accelerator choice.

## Not done yet

- Not yet run on a real phone: GPU delegate, latency, memory and heat still to measure.
- Saving meals / own test-set logging, INT8 model, depth (Stage 2).
