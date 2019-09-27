using Odyssey_Downloader.Model;
using System.Collections.Generic;
using System.Linq;

namespace Odyssey_Downloader
{
    public interface IIndexReader
    {
        IEnumerable<AudioFile> RebuildIndex();

        IEnumerable<AudioFile> ReadIndex();

        void WriteToIndex(AudioFile fileInfo);

        bool IndexDetected();
    }
    public static class IndexReaderExtensions
    {
        public static bool HasTitle(this IIndexReader reader, string title)
        {
            var items = reader.ReadIndex();
            var findTitle = items.Where(x => x.Title == title);
            return findTitle.Any();
        }
    }
}