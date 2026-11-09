// 宣传片：点按封面后带声音播放，随后交给浏览器原生控件。
const video = document.getElementById('film-video') as HTMLVideoElement | null;
const play = document.getElementById('film-play');
if (video && play) {
  play.addEventListener('click', () => {
    play.remove();
    video.controls = true;
    video.play().catch(() => {});
    video.focus();
  });
}
