/* 个股 K 线页:拉 /api/kline -> 转 ECharts option -> 重绘蜡烛图 + 成交量副图。
   纯原生 JS,依赖全局 echarts(由 echarts.min.js 提供)。 */
(function () {
  'use strict';

  var root = document.getElementById('kline');
  if (!root || typeof echarts === 'undefined') {
    return;
  }

  var MARKET = root.getAttribute('data-market') || 'us';
  var CODE = root.getAttribute('data-code') || '';

  var UP = '#dc2626';   // 阳线:涨红
  var DOWN = '#16a34a'; // 阴线:跌绿

  var chart = echarts.init(root);
  var currentPeriod = '1d';

  window.addEventListener('resize', function () {
    chart.resize();
  });

  function showMessage(text) {
    chart.clear();
    chart.setOption({
      title: {
        text: text,
        left: 'center',
        top: 'center',
        textStyle: { color: '#64748b', fontSize: 14, fontWeight: 'normal' }
      }
    });
  }

  // points: [{time, open, high, low, close, volume}, ...]
  function render(points) {
    if (!points || points.length === 0) {
      showMessage('暂无数据(可能数据源限流或本机代理拦截,A股本地联调常见)');
      return;
    }

    var times = [];
    var candles = [];   // ECharts 蜡烛图固定顺序 [open, close, low, high]
    var volumes = [];

    for (var i = 0; i < points.length; i++) {
      var p = points[i];
      times.push(p.time);
      candles.push([p.open, p.close, p.low, p.high]);
      volumes.push({
        value: p.volume,
        itemStyle: { color: p.close >= p.open ? UP : DOWN }
      });
    }

    chart.clear();
    chart.setOption({
      animation: false,
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'cross' }
      },
      axisPointer: { link: [{ xAxisIndex: 'all' }] },
      grid: [
        { left: 56, right: 24, top: 24, height: '60%' },
        { left: 56, right: 24, top: '74%', height: '16%' }
      ],
      xAxis: [
        {
          type: 'category', data: times, gridIndex: 0,
          boundaryGap: true, axisLine: { onZero: false },
          axisLabel: { show: false }
        },
        {
          type: 'category', data: times, gridIndex: 1,
          boundaryGap: true, axisLine: { onZero: false }
        }
      ],
      yAxis: [
        { scale: true, gridIndex: 0, splitArea: { show: true } },
        {
          scale: true, gridIndex: 1, splitNumber: 2,
          axisLabel: { show: false }, axisLine: { show: false },
          axisTick: { show: false }, splitLine: { show: false }
        }
      ],
      dataZoom: [
        { type: 'inside', xAxisIndex: [0, 1], start: 60, end: 100 },
        { type: 'slider', xAxisIndex: [0, 1], top: '94%', start: 60, end: 100 }
      ],
      series: [
        {
          name: 'K线', type: 'candlestick',
          xAxisIndex: 0, yAxisIndex: 0,
          data: candles,
          itemStyle: {
            color: UP, color0: DOWN,
            borderColor: UP, borderColor0: DOWN
          }
        },
        {
          name: '成交量', type: 'bar',
          xAxisIndex: 1, yAxisIndex: 1,
          data: volumes
        }
      ]
    });
  }

  function load(period) {
    if (!CODE) {
      showMessage('暂无数据(可能数据源限流或本机代理拦截,A股本地联调常见)');
      return;
    }
    currentPeriod = period;
    showMessage('加载中…');
    var url = '/api/kline?market=' + encodeURIComponent(MARKET)
      + '&code=' + encodeURIComponent(CODE)
      + '&period=' + encodeURIComponent(period);
    fetch(url)
      .then(function (r) { return r.ok ? r.json() : []; })
      .then(function (data) { render(data); })
      .catch(function () { render([]); });
  }

  // 周期按钮:点击切换 + 高亮当前档
  var buttons = document.querySelectorAll('.period-btn');
  for (var b = 0; b < buttons.length; b++) {
    (function (btn) {
      btn.addEventListener('click', function () {
        for (var k = 0; k < buttons.length; k++) {
          buttons[k].classList.remove('active');
        }
        btn.classList.add('active');
        load(btn.getAttribute('data-period'));
      });
    })(buttons[b]);
  }

  load('1d'); // 默认日线
})();
