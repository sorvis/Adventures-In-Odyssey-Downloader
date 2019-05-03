using NLog;
using Odyssey_Downloader;
using System.Linq;

namespace OdysseyDownloader.FileReader
{
    public class FileReaderFactory
    {
        private readonly Logger _logger = LogManager.GetCurrentClassLogger();
        private readonly FileIndexV1[] _readers;

        public FileReaderFactory(Config config)
        {
            _readers = new[] { new FileIndexV1(config) };
        }

        public IIndexReader Get()
        {
            IIndexReader result = _readers.Last();
            foreach(var reader in _readers)
            {
                if (reader.IndexDetected()) {
                    result = reader;
                    break;
                }
            }
            _logger.Trace($"Choose index reader: {result.GetType().FullName}");
            return result;
        }
    }
}
