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
- **Model**: *Western model* (default), *Western vs. v1 pad (compare)*, or the first models
  *v1 center crop* (square from the middle, edges cut off), *v1 pad to square* (whole photo,
  grey bars) and *v1 both crops (compare)*. The compare modes run two models on every photo;
  thumbnails, live overlay, benchmark and a comparison card on the result show both
  estimates. Each model gets the photo prepared the way it was trained.
- **Accelerator**: Auto (GPU, CPU fallback), GPU only or CPU only; the models reload.
- **Benchmark**: 50 runs on the last photo (or a grey image): median, mean, p90, fastest.
- **Model**: file, size, input, precision, load time, memory, app version.

Settings are kept in SharedPreferences.

### Depth tab (Stage 2 benchmark capture)

Collects photos with ARCore depth to test monocular depth models at table distance
(30 cm to 1 m) on this phone. Needs Google Play Services for AR (ARCore Depth API; the
Pixel 8 Pro supports it); the rest of the app works without it.

- The live overlay shows tracking state, number of detected table planes, the distance to
  the table along the ray through the centre mark, the camera height above the table and
  ARCore's depth at the centre. Move the phone slowly for a second until a plane and depth
  appear.
- **Capture** saves one folder per photo: `rgb.jpg` (largest ARCore CPU image),
  `depth.pgm` (smoothed depth) and `raw_depth.pgm` (16-bit, millimetres, 0 = none),
  `raw_confidence.pgm` (8-bit) and `meta.json` (image intrinsics, camera pose, all
  tracked horizontal planes with normal, polygon and camera height, centre hit). All images
  are in the camera sensor orientation (landscape); depth covers the same field of view at
  a lower resolution.
- **Export** zips all captures to `Download/FoodDepth_<date>.zip` (copy over USB or upload to
  Drive). **Delete** removes the captures from the phone.

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

`app/src/main/assets/` holds three models (MobileNetV3-Large, 320 px, FP16, trained on
cleaned labels) and their contracts:

| Files | Preprocessing | Dishes / ingredients | Test score (desktop check) |
|---|---|---|---|
| `food_stage1_western_fp16.tflite` + `food_stage1_western.json` (default) | pad to square | 1,300 / 862 | **23.56** |
| `food_stage1_center_fp16.tflite` + `food_stage1_center.json` (v1) | shorter side to 320, centre crop | 49 / 170 | 24.65 |
| `food_stage1_pad_fp16.tflite` + `food_stage1_pad.json` (v1) | pad to square with RGB (124, 116, 104), resize to 320 × 320 | 49 / 170 | 24.26 |

The Western model (`runs/western/mm_f101_s1`, 16 MB) is also trained on MM-Food-100K and
Food-101 for dish / ingredient recognition only (never for weight or nutrition) and has its own
1024-wide recognition layer. On the test sets it finds the right dish first in 81 % of the photos
(v1: 74 %) and on held-out Food-101 photos in 54 % (top 5: 79 %).

Input float32 `[1, 320, 320, 3]` RGB in 0..1 (ImageNet normalisation is inside the model).
Output one vector `[mass_g, kcal, fat_g, carb_g, protein_g, log_mass, log_std90, dish probs...,
ingredient probs...]` (v1: 50 + 171, Western: 1,301 + 863, each including "other"). The JSON holds labels, input size, crop mode, output layout and calibration.

To update them, export from the training repository and copy the files:

```bash
cd ../OnDeviceFoodRegEst
E=~/.cache/ondevice_food/export-venv/bin/python
PYTHONPATH=. $E -m training.export_litert runs/clean/mobilenetv3_large_img320_s0/best.pt --out export/food_stage1_center
PYTHONPATH=. $E -m training.export_litert runs/clean/mobilenetv3_large_img320_pad_s0/best.pt --out export/food_stage1_pad
PYTHONPATH=. $E -m training.export_litert runs/western/mm_f101_s1/best.pt --out export/food_stage1_western
cp export/food_stage1_{center,pad,western}.json export/food_stage1_{center,pad,western}_fp16.tflite ../OnDeviceFoodRegEstApp/app/src/main/assets/
```

Checked on the desktop with `training.eval_litert` (`--crop pad` for the pad model): both FP16
files score the same as their PyTorch checkpoints on the cleaned test sets (side views 60 %).

## Code

- `ml/FoodEstimator.kt`: loads the model (GPU → CPU fallback, warm-up), runs one photo.
- `ml/Preprocess.kt`: decoding with EXIF rotation, resize + centre crop, tensor.
- `ml/MealEstimate.kt`: per-photo output, median over photos, weight correction.
- `ui/`: capture, result and settings screens; `MainViewModel`.
- `settings/Settings.kt`: persisted settings, accelerator choice.
- `depth/ArDepthCapture.kt`: ARCore session, camera background, capture and ZIP export;
  `ui/DepthCaptureScreen.kt` is its screen.

## Not done yet

- Not yet run on a real phone: GPU delegate, latency, memory and heat still to measure.
- Saving meals / own test-set logging, INT8 model, depth as model input (Stage 2).
- Depth tab built and compiled, not yet tried on the phone.
