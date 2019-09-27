using System;
using System.IO;
using System.Text.RegularExpressions;

namespace Odyssey_Downloader
{
    public class GetFileInfo : IFileInfo
    {
        private string _title;
        private readonly string _newFileName;
        private CGWebClient _webClient = new CGWebClient();


        public GetFileInfo(Config settings, int dayOffset)
        {
            var contentsPage = getPageSource(settings.Url);
            var targetDate = returnDate(dayOffset, settings.DateFormat);
            if (!contentsPage.Contains(targetDate))
            {
                return;
            }

            var contentsTargetSection = findElement(contentsPage, targetDate, "href=\"");
            var targetUrl = "https" + findElement(contentsTargetSection.Replace(".html", ".html_END_"), "_END_", "https");

            var targetPage = getPageSource(targetUrl);
            FileUrl = findElement(targetPage, ".mp3", "encodedFileUrl: '") + ".mp3";
            EpisodeNumber = findElement(targetPage, ",\r\n        encodedFileUrl", "episodeId: ");
            _title = findElement(targetPage, "</title>", "<title>").
                Replace(" - Listen to Listen to Adventures in Odyssey from Focus on The Family Radio Online, ", "").
                Replace(targetDate, "");

            Title = "Episode " + EpisodeNumber + ": " + _title;
            var fileName = EpisodeNumber + "#-" + _title.Replace(" ", "_") + settings.FileExtension;

            var illegalInFileName = new Regex(string.Format("[{0}]", Regex.Escape(new string(Path.GetInvalidFileNameChars()))), RegexOptions.Compiled);
            _newFileName = illegalInFileName.Replace(fileName, "");
        }

        public string FileUrl { get; }


        public string EpisodeNumber { get; }

        public string Title { get; }

        public string FileName => filterOutInvalidFileNameChars(_newFileName);

        private static string ReverseString(string s)
        {
            char[] arr = s.ToCharArray();
            Array.Reverse(arr);
            return new string(arr);
        }

        private static string filterOutInvalidFileNameChars(string fileName)
        {
            if (string.IsNullOrEmpty(fileName))
            {
                return string.Empty;
            }

            string invalidChars = Regex.Escape(new string(Path.GetInvalidFileNameChars()));
            string invalidReStr = string.Format(@"([{0}]*\.+$)|([{0}]+)", invalidChars);
            return Regex.Replace(fileName, invalidReStr, "_");
        }

        private string getPageSource(string url)
        {
            try
            {
                //System.Net.WebClient webClient = new System.Net.WebClient();
                //webClient.Headers.Add ("user-agent", "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.2; .NET CLR 1.0.3705;)");
                string strSource = _webClient.DownloadString(url);
                _webClient.Dispose();

                return strSource;
            }
            catch (Exception e)
            {
                Console.WriteLine(e);
                return getPageSource(url);
            }
        }
        private string returnDate(int dayOffset, string dateFormat)
        {
            DateTime dt = DateTime.Today.AddDays(-dayOffset);
            string date = dt.ToString(dateFormat);
            return date;
        }

        private string findElement(string Source, string elementEnd, string elementStart)
        {
            if(!Source.Contains(elementEnd) || !Source.Contains(elementStart)) return string.Empty;
            int extensionIndex = Source.IndexOf(elementEnd);
            var endCutOff = Source.Substring(0, extensionIndex); //cut off source after file
            var reversedEndCutOff = ReverseString(endCutOff); // reverse the source so that the file url is first
            int httpIndex = reversedEndCutOff.IndexOf(ReverseString(elementStart));
            var cutOffBeforeReversed = reversedEndCutOff.Substring(0, httpIndex); //cut off source before file
            Source = ReverseString(cutOffBeforeReversed); // reverse the URL back to normal

            return Source;
        }
    }
}
