import React from 'react';
import PropTypes from 'prop-types';

class EntryLightboxBanner extends React.Component {
    static propTypes = {
        lightboxEntries: PropTypes.array.isRequired,
        entryClassName: PropTypes.string
    };

    render(){
        return <span className="entry-lightbox-banner">
            {
                this.props.lightboxEntries.map(entry=>
                    <img src={entry.avatarUrl ? entry.avatarUrl : "/static/default-avatar.png"}
                         alt={entry.owner}
                         className={this.props.entryClassName ? this.props.entryClassName : "entry-lightbox-banner-entry" }
                    />
                )
            }
        </span>
    }
}

export default EntryLightboxBanner;