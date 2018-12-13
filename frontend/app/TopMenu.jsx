import React from 'react';
import PropTypes from 'prop-types';
import { Link } from 'react-router-dom';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'

class TopMenu extends React.Component {
    static propTypes = {
        visible: PropTypes.bool.isRequired,
        isAdmin: PropTypes.bool.isRequired
    };

    render(){
        return <div style={{display: this.props.visible ? "block" : "none"}} className="top-menu">
            <Link className="top-menu-spacer" to="/search"><FontAwesomeIcon className="smallicon inline-icon highlight" icon="search"/>Search</Link>
            <Link className="top-menu-spacer" to="/browse"><FontAwesomeIcon className="smallicon inline-icon highlight" icon="th-list"/>Browse</Link>
            <Link className="top-menu-spacer" to="/lightbox"><FontAwesomeIcon className="smallicon inline-icon highlight" icon="lightbulb"/>My Lightbox</Link>
            <Link className="top-menu-spacer" to="/admin" style={{display: this.props.isAdmin ? "inline" : "none"}}><FontAwesomeIcon className="smallicon inline-icon highlight" icon="wrench"/>Admin</Link>
        </div>
    }
}

export default TopMenu;