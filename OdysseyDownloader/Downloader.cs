using System.Net;

namespace Odyssey_Downloader
{
    public class Downloader : IDownloader
    {
        public void GetFile(string url, string fileName)
        {
            WebClient Client = new WebClient();
            Client.DownloadFile(url, fileName);
            Client.Dispose();
        }
    }
}
