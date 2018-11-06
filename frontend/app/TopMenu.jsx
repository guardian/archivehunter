import React from 'react';
import PropTypes from 'prop-types';

class TopMenu extends React.Component {
    static propTypes = {
        visible: PropTypes.bool.isRequired,
        isAdmin: PropTypes.bool.isRequired
    };

    render(){
        return <div style={{display: this.props.visible ? "block" : "none"}} className="top-menu">
            <span className="top-menu-spacer"><img className="smallicon inline-icon" src="images/search.png"/>Search</span>
            <span className="top-menu-spacer"><img className="smallicon inline-icon" src="images/browse.png"/>Browse</span>
            <span className="top-menu-spacer"><img className="smallicon inline-icon" src="images/lightbox.png"/>My Lightbox</span>
            <span className="top-menu-spacer" style={{display: this.props.visible ? "inline" : "none"}}><img className="smallicon inline-icon" src="images/admin.png"/>Admin</span>
        </div>
    }
}

export default TopMenu;