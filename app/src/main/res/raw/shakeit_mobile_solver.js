(function () {
    console.log("🚀 Starting ShakeIt Mobile Solver...");

    // 配置参数 (与 Node.js 版本一致)
    const CONFIG = {
        fineZone: 0.05,
        targetFine: 0.005,
        kpFine: 40,
        kdFine: 15,
        maxFineGamma: 3,
        minKick: 1.0,
        brakingFactor: 0.35,
        loopInterval: 20
    };

    let lastTime = Date.now();
    let lastCurrent = 0.5;
    let isActive = true;

    // 定时器循环
    const intervalId = setInterval(() => {
        // 1. 检查是否成功
        const overlay = document.getElementById('successOverlay');
        if (overlay && overlay.classList.contains('show')) {
            console.log("✅ Verification Success!");
            stop();
            return;
        }

        // 2. 获取 DOM 状态
        const targetEl = document.getElementById('targetValue');
        const currentEl = document.getElementById('currentPosition');

        if (!targetEl || !currentEl) return;

        const target = parseFloat(targetEl.textContent || '0');
        const current = parseFloat(currentEl.textContent || '0.5');

        // 3. 计算物理量
        const currentTime = Date.now();
        let dt = (currentTime - lastTime) / 1000;
        if (dt <= 0) dt = 0.016;

        const error = target - current;
        const velocity = (current - lastCurrent) / dt;

        lastTime = currentTime;
        lastCurrent = current;

        // 4. 控制算法 (移植自 solve.js)
        let gamma = 0;

        // --- Fine Tuning Zone ---
        if (Math.abs(error) < CONFIG.fineZone) {
            if (Math.abs(error) < 0.005 && Math.abs(velocity) < 0.02) {
                gamma = 0; // Hold
            } else {
                // PD Controller
                gamma = (error * CONFIG.kpFine) - (velocity * CONFIG.kdFine);

                // Min Kick
                if (Math.abs(error) > 0.005 && Math.abs(gamma) < CONFIG.minKick) {
                    gamma = Math.sign(error) * CONFIG.minKick;
                }

                // Clamp
                if (gamma > CONFIG.maxFineGamma) gamma = CONFIG.maxFineGamma;
                if (gamma < -CONFIG.maxFineGamma) gamma = -CONFIG.maxFineGamma;
            }
        } else {
            // --- Approach Zone ---
            const directionToTarget = Math.sign(error);
            const movingTowards = Math.sign(velocity) === directionToTarget;

            if (movingTowards) {
                // Braking
                const brakingDistance = Math.abs(velocity) * CONFIG.brakingFactor;
                if (Math.abs(error) < brakingDistance) {
                    gamma = -directionToTarget * 10;
                } else {
                    if (Math.abs(velocity) > 0.5) {
                        gamma = 0;
                    } else {
                        gamma = directionToTarget * 10;
                    }
                }
            } else {
                // Accelerate
                gamma = directionToTarget * 15;
            }
        }

        // Global Clamp
        if (gamma > 20) gamma = 20;
        if (gamma < -20) gamma = -20;

        // 5. 发送指令给 LSPosed
        // 优先使用 document.title 作为通道（更稳定，不会触发重复 navigation 回调）
        // 格式必须匹配 Java 代码中的 "LSP_CMD:"
        document.title = "LSP_CMD:" + gamma.toFixed(2);
        // 兼容通道：仍输出一份到日志，便于在 Console 里观察
        console.error("LSP_CMD:" + gamma.toFixed(2));

        // 可视化调试 (可选)
        // document.title = `E:${error.toFixed(3)} G:${gamma.toFixed(1)}`;

    }, CONFIG.loopInterval);

    function stop() {
        isActive = false;
        clearInterval(intervalId);
        document.title = "LSP_STOP";
        console.error("LSP_STOP"); // 告诉模块停止干预
    }

    // 暴露给全局以便手动停止
    window.stopShakeSolver = stop;

})();
