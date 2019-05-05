using System;
using System.IO;
using System.Text.RegularExpressions;

namespace Odyssey_Downloader
{
    internal class GetFileInfo
    {
        private string pageSource;
        private string fileUrl;
        private string title;
        private string episodeNumber;
        private string fullTitle;
        private string newFileName;
		CGWebClient webClient = new CGWebClient();

        public string GetPageSource()
        {
            return pageSource;
        }

        public string GetFileUrl()
        {
            return fileUrl;
        }

        public string GetTitle()
        {
            return title;
        }

        public string GetEpisodeNumber()
        {
            return episodeNumber;
        }

        public string GetFullTitle()
        {
            return fullTitle;
        }

        public string FileName
        {
            get
            {
                return filterOutInvalidFileNameChars(newFileName);
            }
        }

        private static string filterOutInvalidFileNameChars(string fileName)
        {
            if (string.IsNullOrEmpty(fileName)) return string.Empty;
            string invalidChars = System.Text.RegularExpressions.Regex.Escape(new string(System.IO.Path.GetInvalidFileNameChars()));
            string invalidReStr = string.Format(@"([{0}]*\.+$)|([{0}]+)", invalidChars);
            return System.Text.RegularExpressions.Regex.Replace(fileName, invalidReStr, "_");
        }

        private string getPageSource(string url)
        {
            try
			{
				//System.Net.WebClient webClient = new System.Net.WebClient();
				//webClient.Headers.Add ("user-agent", "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.2; .NET CLR 1.0.3705;)");
	            string strSource = webClient.DownloadString(url);
	            webClient.Dispose();

				return strSource;
			}
			catch (Exception e)
			{
                Console.WriteLine(e);
				return getPageSource(url);
			}
        }

        public GetFileInfo(Config settings, int dayOffset)
        {
            var contentsPage = getPageSource(settings.Url);
            var targetDate = returnDate(dayOffset, settings.DateFormat);
            if (!contentsPage.Contains(targetDate)) return;

            var contentsTargetSection = findElement(contentsPage, targetDate, "href=\"");
            var targetUrl = "https" + findElement(contentsTargetSection.Replace(".html",".html_END_"), "_END_", "https");

            var targetPage = getPageSource(targetUrl);
            fileUrl = findElement(targetPage, ".mp3", "encodedFileUrl: '") + ".mp3";
            episodeNumber = findElement(targetPage, ",\r\n        encodedFileUrl", "episodeId: ");
            title = findElement(targetPage, "</title>", "<title>").
                Replace(" - Listen to Listen to Adventures in Odyssey from Focus on The Family Radio Online, ", "").
                Replace(targetDate, "");

            fullTitle = "Episode " + episodeNumber + ": " + title;
            var fileName = episodeNumber + "#-" + title.Replace(" ", "_")+settings.FileExtension;

            var illegalInFileName = new Regex(string.Format("[{0}]", Regex.Escape(new string(Path.GetInvalidFileNameChars()))), RegexOptions.Compiled);
            newFileName = illegalInFileName.Replace(fileName, "");
        }

        private string returnDate(int dayOffset, string dateFormat)
        {
            DateTime dt = DateTime.Today.AddDays(-dayOffset);
            string date = string.Format(dateFormat, dt);
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

        private static string ReverseString(string s)
        {
            char[] arr = s.ToCharArray();
            Array.Reverse(arr);
            return new string(arr);
        }
    }
}
