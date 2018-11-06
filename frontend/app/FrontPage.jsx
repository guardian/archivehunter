import React from 'react';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'

class FrontPage extends React.Component {
    render(){
        return <div className="centered">
            <h1 style={{textAlign: "center"}}>ArchiveHunter</h1>
            <span className="login-icon"> <FontAwesomeIcon className="login-icon" size={70} icon="road"/></span>
            <p style={{textAlign: "center"}}>Work In Progress</p>
        </div>
    }
}

export default FrontPage;